/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.ExperimentalKompressorApi
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.requireFilePathOrThrow
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.suspendRunCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Android image compressor backed by [BitmapFactory] and [Bitmap.compress]. */
@OptIn(ExperimentalKompressorApi::class)
internal class AndroidImageCompressor(
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
) : ImageCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        logger.instrumentCompress(
            tag = LogTags.IMAGE,
            startMessage = {
                "compress() start in=$inputPath out=$outputPath " +
                    "fmt=${config.format} quality=${config.quality} " +
                    "max=${config.maxWidth}x${config.maxHeight} aspect=${config.keepAspectRatio}"
            },
            successMessage = { r ->
                "compress() ok durationMs=${r.durationMs} " +
                    "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
            },
            failureMessage = { "compress() failed in=$inputPath" },
        ) {
            try {
                doCompress(inputPath, outputPath, config)
            } catch (e: ImageCompressionError) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                throw classifyAndroidImageError(inputPath, e)
            }
        }
    }

    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        val inputPath = input.requireFilePathOrThrow()
        val outputPath = output.requireFilePathOrThrow()
        compress(inputPath, outputPath, config).getOrThrow()
    }

    private suspend fun doCompress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult {
        androidOutputGate(config.format, Build.VERSION.SDK_INT)?.let { throw it }

        val startNanos = System.nanoTime()

        val source = ImageSource.of(inputPath)
        val inputSize = source.size()
        val detectedFormat = detectInputImageFormat(source.readHeader(), fileExtension(inputPath))
        androidInputGate(detectedFormat, Build.VERSION.SDK_INT)?.let { throw it }

        val exifRotation = source.readExifRotation()
        val rawDims = source.decodeRawDimensions()
        val orientedDims = applyRotationToDimensions(rawDims, exifRotation)
        currentCoroutineContext().ensureActive()

        val target = calculateTargetDimensions(
            orientedDims.width, orientedDims.height,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val bitmap = source.decodeSampledBitmap(rawDims, target, exifRotation)
        try {
            currentCoroutineContext().ensureActive()
            resizeAndWrite(bitmap, target, outputPath, config)
        } finally {
            bitmap.recycle()
        }

        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        return CompressionResult(inputSize, outputSize, durationMs)
    }

    private fun applyRotationToDimensions(dims: ImageDimensions, rotation: ExifRotation): ImageDimensions =
        if (rotation.swapsDimensions) ImageDimensions(dims.height, dims.width) else dims

    private fun resizeAndWrite(
        bitmap: Bitmap,
        target: ImageDimensions,
        outputPath: String,
        config: ImageCompressionConfig,
    ) {
        val scaled = resizeBitmapIfNeeded(bitmap, target)
        try {
            writeBitmap(scaled, outputPath, config)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, target: ImageDimensions): Bitmap {
        if (bitmap.width == target.width && bitmap.height == target.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
    }

    private fun writeBitmap(bitmap: Bitmap, outputPath: String, config: ImageCompressionConfig) {
        val compressFormat = androidCompressFormat(config.format)
        // Capture the compress result inside `use { }` so the stream is closed before we throw.
        // Returning the boolean and then throwing after close moves the error path to a point
        // where the I/O resource is provably released — the Bitmap ownership contract lives with
        // the caller (`resizeAndWrite` recycles `scaled`; the outer compress() recycles `bitmap`).
        val success = FileOutputStream(outputPath).use { stream ->
            bitmap.compress(compressFormat, config.quality, stream)
        }
        if (!success) {
            throw ImageCompressionError.EncodingFailed(
                "Bitmap.compress(${config.format}, quality=${config.quality}) returned false for: $outputPath",
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Maps the library's [ImageFormat] to the platform [Bitmap.CompressFormat]. The gate
 * in [androidOutputGate] has already rejected formats unavailable on the current API level,
 * so every branch here is reachable at its respective minimum SDK.
 */
@OptIn(ExperimentalKompressorApi::class)
private fun androidCompressFormat(format: ImageFormat): Bitmap.CompressFormat = when (format) {
    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ImageFormat.WEBP -> androidWebPFormat()
    ImageFormat.AVIF -> androidAvifFormat()
    ImageFormat.HEIC -> error("HEIC output should be rejected by androidOutputGate before reaching here")
}

// `Bitmap.CompressFormat.WEBP` is deprecated as of API 30 in favour of `WEBP_LOSSY` /
// `WEBP_LOSSLESS`, but still works on API 24 where the new variants don't exist. Pick the
// lossy variant above API 30 since we expose a single `quality` parameter.
@Suppress("DEPRECATION")
private fun androidWebPFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }

// `Bitmap.CompressFormat.AVIF` is API 34+; the androidOutputGate has already thrown on older.
// Use valueOf to avoid a compile-time dependency on compileSdk ≥ 34 (we build against 36,
// which provides it, so this is just insurance against downgrades).
private fun androidAvifFormat(): Bitmap.CompressFormat =
    Bitmap.CompressFormat.valueOf("AVIF")

/**
 * Describes the EXIF orientation transform to apply to raw pixel data.
 * Defaults to identity (no transform).
 */
private data class ExifRotation(
    val degrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {
    val isIdentity: Boolean get() = degrees == 0f && scaleX == 1f && scaleY == 1f
    val swapsDimensions: Boolean get() = degrees == 90f || degrees == 270f
}

/**
 * Abstracts the two input-path forms the compressor accepts — filesystem paths (`/sdcard/…`,
 * `file://…`) and `content://` URIs delivered by share sheets / the photo picker / SAF. Both
 * code paths need the same five operations (`size()`, `readHeader()`, `readExifRotation()`,
 * `decodeRawDimensions()`, `decodeSampledBitmap(...)`); dispatching through a sealed interface
 * keeps the compressor body linear while the two file-access layers (java.io.File vs
 * ContentResolver) stay isolated.
 */
private sealed interface ImageSource {

    fun size(): Long
    fun readHeader(): ByteArray
    fun readExifRotation(): ExifRotation
    fun decodeRawDimensions(): ImageDimensions
    fun decodeSampledBitmap(
        rawDims: ImageDimensions,
        target: ImageDimensions,
        exifRotation: ExifRotation,
    ): Bitmap

    companion object {
        fun of(inputPath: String): ImageSource =
            if (inputPath.startsWith("content://")) ContentUriSource(Uri.parse(inputPath))
            else FilePathSource(inputPath.removePrefix("file://"))
    }
}

/** File-path source: mirrors the pre-refactor behaviour exactly so existing paths are untouched. */
private class FilePathSource(private val path: String) : ImageSource {

    override fun size(): Long = File(path).length()

    override fun readHeader(): ByteArray = try {
        File(path).inputStream().use { it.readFirstBytes(IMAGE_SNIFF_BYTES) }
    } catch (e: FileNotFoundException) {
        throw ImageCompressionError.IoFailed("Input file not found: $path", e)
    }

    override fun readExifRotation(): ExifRotation = decodeExifOrientation(ExifInterface(path))

    override fun decodeRawDimensions(): ImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            if (!File(path).exists()) {
                throw ImageCompressionError.IoFailed("Input file not found: $path")
            }
            throw ImageCompressionError.DecodingFailed("Cannot decode image dimensions: $path")
        }
        return ImageDimensions(options.outWidth, options.outHeight)
    }

    override fun decodeSampledBitmap(
        rawDims: ImageDimensions,
        target: ImageDimensions,
        exifRotation: ExifRotation,
    ): Bitmap {
        val options = buildSampledDecodeOptions(rawDims, target, exifRotation)
        val decoded = BitmapFactory.decodeFile(path, options)
            ?: throw ImageCompressionError.DecodingFailed("Failed to decode image: $path")
        return rotateOrRecycle(decoded, exifRotation)
    }
}

/** `content://` URI source — goes through [android.content.ContentResolver]. */
private class ContentUriSource(private val uri: Uri) : ImageSource {

    private val resolver by lazy { KompressorContext.appContext.contentResolver }

    // `0L` on open-failure matches the library-wide contract in `resolveMediaInputSize`
    // (audio + video paths use the same fallback): `inputSize` is a reported metric on
    // `CompressionResult`, not an invariant the decode depends on. If the URI is genuinely
    // unreadable the subsequent `decodeRawDimensions()` call will throw
    // "Cannot decode image dimensions" with the full URI — the real error surfaces there.
    override fun size(): Long = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
        pfd.statSize.coerceAtLeast(0L)
    } ?: 0L

    override fun readHeader(): ByteArray = openStream().use { it.readFirstBytes(IMAGE_SNIFF_BYTES) }

    override fun readExifRotation(): ExifRotation = openStream().use { stream ->
        decodeExifOrientation(ExifInterface(stream))
    }

    override fun decodeRawDimensions(): ImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ImageCompressionError.DecodingFailed("Cannot decode image dimensions: $uri")
        }
        return ImageDimensions(options.outWidth, options.outHeight)
    }

    override fun decodeSampledBitmap(
        rawDims: ImageDimensions,
        target: ImageDimensions,
        exifRotation: ExifRotation,
    ): Bitmap {
        val options = buildSampledDecodeOptions(rawDims, target, exifRotation)
        val decoded = openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
                ?: throw ImageCompressionError.DecodingFailed("Failed to decode image: $uri")
        }
        return rotateOrRecycle(decoded, exifRotation)
    }

    private fun openStream(): InputStream =
        resolver.openInputStream(uri)
            ?: throw ImageCompressionError.IoFailed("ContentResolver returned null input stream for $uri")
}

private fun InputStream.readFirstBytes(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(buffer, offset, count - offset)
        if (read <= 0) break
        offset += read
    }
    return if (offset == count) buffer else buffer.copyOf(offset)
}

private fun decodeExifOrientation(exif: ExifInterface): ExifRotation =
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifRotation(degrees = 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifRotation(degrees = 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifRotation(degrees = 270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifRotation(scaleX = -1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifRotation(scaleY = -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifRotation(degrees = 90f, scaleX = -1f)
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifRotation(degrees = 270f, scaleX = -1f)
        else -> ExifRotation()
    }

private fun buildSampledDecodeOptions(
    rawDims: ImageDimensions,
    target: ImageDimensions,
    exifRotation: ExifRotation,
): BitmapFactory.Options {
    // Apply EXIF rotation to raw dims so the sample-size heuristic targets the post-rotation
    // visible bitmap dimensions. Without this, a 4000×3000 JPEG tagged ROTATE_90 would be
    // sample-sized as if it were 4000×3000 even though the visible image is 3000×4000.
    val oriented = if (exifRotation.swapsDimensions) {
        ImageDimensions(rawDims.height, rawDims.width)
    } else {
        rawDims
    }
    return BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(oriented.width, oriented.height, target.width, target.height)
    }
}

private fun rotateOrRecycle(decoded: Bitmap, rotation: ExifRotation): Bitmap = try {
    if (rotation.isIdentity) {
        decoded
    } else {
        val matrix = Matrix().apply {
            postRotate(rotation.degrees)
            postScale(rotation.scaleX, rotation.scaleY)
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        rotated
    }
} catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
    decoded.recycle()
    throw e
}

/**
 * Classifies a Throwable raised from the Android image pipeline into the appropriate
 * [ImageCompressionError] subtype. Already-typed errors are rethrown unchanged by the caller
 * before this runs; this handles the raw platform exceptions.
 */
private fun classifyAndroidImageError(inputPath: String, e: Throwable): ImageCompressionError = when (e) {
    is FileNotFoundException -> ImageCompressionError.IoFailed("Input not found: $inputPath", e)
    is IOException -> ImageCompressionError.IoFailed(e.message ?: "I/O error reading $inputPath", e)
    is OutOfMemoryError -> ImageCompressionError.DecodingFailed(
        "Out of memory decoding $inputPath",
        e,
    )
    else -> ImageCompressionError.Unknown(e.message ?: e::class.simpleName.orEmpty(), e)
}
