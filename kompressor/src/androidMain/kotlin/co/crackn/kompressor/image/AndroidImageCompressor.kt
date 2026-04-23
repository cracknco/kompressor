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
import co.crackn.kompressor.io.MediaType
import co.crackn.kompressor.io.toAndroidInputPath
import co.crackn.kompressor.io.toAndroidOutputHandle
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.logging.redactPath
import co.crackn.kompressor.suspendRunCatching
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Android image compressor backed by [BitmapFactory] and [Bitmap.compress]. */
// TooManyFunctions suppressed: CRA-95 added the Stream/Bytes short-circuit helpers
// (`compressFromBytes`, `compressFromPath`, `compressPathToStream`, `compressPathToFileBacked`)
// that decode directly via `BitmapFactory.decodeByteArray` / `decodeStream` without the temp-
// file hop. Splitting into a helper class would force the short-circuit helpers to re-export
// the compressor's private encode pipeline — not worth the structural churn.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalKompressorApi::class)
internal class AndroidImageCompressor(
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
) : ImageCompressor {

    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        // Short-circuit: Stream/Bytes inputs decode directly from memory, no temp file. Image
        // payloads are bounded (~50 MB typical), so holding the bytes in RAM is safe and spares
        // the cost of a temp-file round trip the audio / video paths must pay.
        val memoryBytes: ByteArray? = when (input) {
            is MediaSource.Local.Bytes -> input.bytes
            is MediaSource.Local.Stream -> readStreamAsBytes(input)
            else -> null
        }
        if (memoryBytes != null) {
            compressFromBytes(memoryBytes, output, config)
        } else {
            compressFromPath(input, output, config)
        }
    }

    /**
     * Stream/Bytes input path — [memoryBytes] already fully in RAM, no temp file. Dispatches
     * on the destination side separately so Stream destinations also avoid the temp-file hop.
     *
     * Stream-output lifecycle: [MediaDestination.Local.Stream.closeOnFinish] is honoured in the
     * outer `finally` block so the consumer sink is closed on every failure path — including
     * the early-exit decode/gate/EXIF errors that never reach [writeBitmapToSink]. Parallels the
     * iOS sibling's `outHandle.cleanup()` in a `finally` (`IosImageCompressor.kt:182-193`).
     */
    private suspend fun compressFromBytes(
        memoryBytes: ByteArray,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val source = ByteArrayImageSource(memoryBytes)
        return if (output is MediaDestination.Local.Stream) {
            try {
                doCompressDirect(source, config) { bitmap, cfg ->
                    writeBitmapToSink(bitmap, output, cfg)
                }
            } finally {
                if (output.closeOnFinish) runCatching { output.sink.close() }
            }
        } else {
            val outputHandle = output.toAndroidOutputHandle()
            try {
                val result = doCompressDirect(source, config) { bitmap, cfg ->
                    writeBitmapToPath(bitmap, outputHandle.tempPath, cfg)
                    File(outputHandle.tempPath).length()
                }
                outputHandle.commit()
                result
            } finally {
                outputHandle.cleanup()
            }
        }
    }

    /**
     * FilePath / Uri / PFD input path — re-uses the private [compressFilePath] helper when the
     * destination is also file-backed (fastest path, matches pre-CRA-95 behaviour byte-for-byte).
     * When the destination is a [MediaDestination.Local.Stream], decode via [ImageSource.of] and
     * write directly into the consumer [okio.Sink] — still no temp file on the output side.
     *
     * Sink close lifecycle for Stream output lives in [compressPathToStream]'s outer `finally`
     * so it fires even when [doCompressDirect] throws before reaching [writeBitmapToSink]
     * (decode gate, EXIF read, dimension decode). See [compressFromBytes] for the symmetric
     * treatment.
     */
    private suspend fun compressFromPath(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val inputHandle = input.toAndroidInputPath(mediaType = MediaType.IMAGE, logger = logger)
        try {
            return if (output is MediaDestination.Local.Stream) {
                compressPathToStream(inputHandle.path, output, config)
            } else {
                compressPathToFileBacked(inputHandle.path, output, config)
            }
        } finally {
            inputHandle.cleanup()
        }
    }

    private suspend fun compressPathToStream(
        inputPath: String,
        output: MediaDestination.Local.Stream,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val source = ImageSource.of(inputPath)
        return try {
            doCompressDirect(source, config) { bitmap, cfg ->
                writeBitmapToSink(bitmap, output, cfg)
            }
        } finally {
            if (output.closeOnFinish) runCatching { output.sink.close() }
        }
    }

    private suspend fun compressPathToFileBacked(
        inputPath: String,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val outputHandle = output.toAndroidOutputHandle()
        return try {
            val result = compressFilePath(inputPath, outputHandle.tempPath, config)
            // Commit AFTER the CompressionResult is produced so `outputSize` reflects the
            // temp file we just wrote; the commit step only moves bytes, it does not mutate
            // the size reported to the caller.
            outputHandle.commit()
            result
        } finally {
            outputHandle.cleanup()
        }
    }

    /**
     * Local-file → local-file compression with the CRA-47 [instrumentCompress] wrapper applied.
     * Throws the typed [ImageCompressionError] hierarchy on failure — the outer MediaSource
     * dispatch's `suspendRunCatching` converts those throws into `Result.failure`.
     */
    private suspend fun compressFilePath(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.IMAGE,
        startMessage = {
            "compress() start in=${redactPath(inputPath)} out=${redactPath(outputPath)} " +
                "fmt=${config.format} quality=${config.quality} " +
                "max=${config.maxWidth}x${config.maxHeight} aspect=${config.keepAspectRatio}"
        },
        successMessage = { r ->
            "compress() ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
        },
        failureMessage = { "compress() failed in=${redactPath(inputPath)}" },
    ) {
        try {
            doCompress(inputPath, outputPath, config)
        } catch (e: ImageCompressionError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: CancellationException) {
            // Structured concurrency: never wrap cancellation as a typed compression error,
            // or a parent scope cancelling a child compress() would see the cancellation
            // converted into ImageCompressionError.Unknown and silently absorbed by an
            // outer .onFailure handler. Re-throw verbatim so kotlinx.coroutines unwinds.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            throw classifyAndroidImageError(inputPath, e)
        }
    }

    private suspend fun doCompress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val source = ImageSource.of(inputPath)
        return doCompressDirect(source, config) { bitmap, cfg ->
            writeBitmapToPath(bitmap, outputPath, cfg)
            File(outputPath).length()
        }
    }

    /**
     * Shared decode + resize + encode pipeline. The [writer] is the only per-destination
     * variation: it receives the final resized [Bitmap] and returns the number of bytes it
     * wrote (so [CompressionResult.outputSize] is accurate whether the destination was a file
     * or a stream). Errors are surfaced through [classifyAndroidImageError] at the outer
     * `instrumentCompress` catch-sites.
     */
    private suspend fun doCompressDirect(
        source: ImageSource,
        config: ImageCompressionConfig,
        writer: (Bitmap, ImageCompressionConfig) -> Long,
    ): CompressionResult {
        androidOutputGate(config.format, Build.VERSION.SDK_INT)?.let { throw it }

        val startNanos = System.nanoTime()

        val inputSize = source.size()
        val detectedFormat = detectInputImageFormat(source.readHeader(), source.extension())
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
        val outputSize = try {
            currentCoroutineContext().ensureActive()
            resizeAndWrite(bitmap, target, writer, config)
        } finally {
            bitmap.recycle()
        }

        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        return CompressionResult(inputSize, outputSize, durationMs)
    }

    private fun applyRotationToDimensions(dims: ImageDimensions, rotation: ExifRotation): ImageDimensions =
        if (rotation.swapsDimensions) ImageDimensions(dims.height, dims.width) else dims

    private fun resizeAndWrite(
        bitmap: Bitmap,
        target: ImageDimensions,
        writer: (Bitmap, ImageCompressionConfig) -> Long,
        config: ImageCompressionConfig,
    ): Long {
        val scaled = resizeBitmapIfNeeded(bitmap, target)
        return try {
            writer(scaled, config)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, target: ImageDimensions): Bitmap {
        if (bitmap.width == target.width && bitmap.height == target.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
    }

    private fun writeBitmapToPath(bitmap: Bitmap, outputPath: String, config: ImageCompressionConfig) {
        writeBitmapToFile(bitmap, outputPath, config.format, config.quality)
    }

    /**
     * Encode [bitmap] directly into the consumer [MediaDestination.Local.Stream] — no temp
     * file. A [CountingOutputStream] wraps the buffered okio sink so the returned length is
     * the number of bytes actually written, matching [writeBitmapToPath]'s `File.length()`.
     *
     * The buffered sink is `flush()`ed — never `close()`d here. Sink ownership (governed by
     * [MediaDestination.Local.Stream.closeOnFinish]) is enforced by the outer callers
     * ([compressFromBytes] and [compressFromPath]) in their `finally` blocks so the close fires
     * on every exit path, including early decode / gate / EXIF errors that would bypass this
     * writer entirely.
     */
    private fun writeBitmapToSink(
        bitmap: Bitmap,
        dest: MediaDestination.Local.Stream,
        config: ImageCompressionConfig,
    ): Long {
        val compressFormat = androidCompressFormat(config.format)
        val bufferedSink = dest.sink.buffer()
        val counter = CountingOutputStream(bufferedSink.outputStream())
        val success = try {
            bitmap.compress(compressFormat, config.quality, counter)
        } finally {
            runCatching { counter.flush() }
            runCatching { bufferedSink.flush() }
        }
        if (!success) {
            throw ImageCompressionError.EncodingFailed(
                "Bitmap.compress(${config.format}, quality=${config.quality}) returned false " +
                    "while writing to MediaDestination.Local.Stream",
            )
        }
        return counter.bytesWritten
    }

    /**
     * Read a [MediaSource.Local.Stream] into memory, honouring [MediaSource.Local.Stream.closeOnFinish].
     * Images are bounded so holding the payload in RAM is the intended "no temp file" path per
     * CRA-95 — the okio [buffer] accumulates segments in 8 KB chunks, peak heap is the image size.
     */
    private fun readStreamAsBytes(stream: MediaSource.Local.Stream): ByteArray {
        val bufferedSource = stream.source.buffer()
        return try {
            bufferedSource.readByteArray()
        } finally {
            if (stream.closeOnFinish) {
                // Closing the buffered wrapper closes the delegate source too — matching
                // `closeOnFinish = true`.
                runCatching { bufferedSource.close() }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * OutputStream wrapper that counts bytes written without buffering them. Used to compute
 * [CompressionResult.outputSize] for Stream destinations where we cannot `File(...).length()`.
 */
private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len.toLong()
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

/**
 * Shared bitmap → file encoder used by [AndroidImageCompressor] *and* the video-thumbnail path
 * on [co.crackn.kompressor.video.AndroidVideoCompressor.thumbnail]. Both feed a [Bitmap] (one
 * decoded from an image source, the other extracted from a video frame) through the same
 * `bitmap.compress(format, quality, FileOutputStream)` plumbing, so keeping this a single
 * `internal` function means format/quality handling stays in one place.
 *
 * Throws [ImageCompressionError.EncodingFailed] when the platform encoder returns `false`. The
 * video caller catches that and remaps it to [co.crackn.kompressor.video.VideoCompressionError.EncodingFailed]
 * so the image-vs-video typed-error contract is preserved.
 *
 * Assumes the caller has already passed [androidOutputGate] — HEIC still throws a file-scope
 * `error(...)` because `Bitmap.CompressFormat` has no stable HEIC entry; that path should be
 * unreachable from gated call sites.
 */
@OptIn(ExperimentalKompressorApi::class)
internal fun writeBitmapToFile(
    bitmap: Bitmap,
    outputPath: String,
    format: ImageFormat,
    quality: Int,
) {
    val compressFormat = androidCompressFormat(format)
    // Capture the compress result inside `use { }` so the stream is closed before we throw.
    // Returning the boolean and then throwing after close moves the error path to a point
    // where the I/O resource is provably released — the Bitmap ownership contract lives with
    // the caller (the image pipeline recycles the resized bitmap; the thumbnail path recycles
    // the extracted frame).
    val success = FileOutputStream(outputPath).use { stream ->
        bitmap.compress(compressFormat, quality, stream)
    }
    if (!success) {
        throw ImageCompressionError.EncodingFailed(
            "Bitmap.compress($format, quality=$quality) returned false for: $outputPath",
        )
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
 * Abstracts the three input-path forms the compressor accepts — filesystem paths (`/sdcard/…`,
 * `file://…`), `content://` URIs (share sheets / SAF / photo picker), and in-memory byte arrays
 * (CRA-95 Stream/Bytes short-circuit). All three need the same five operations
 * (`size()`, `readHeader()`, `readExifRotation()`, `decodeRawDimensions()`, `decodeSampledBitmap(...)`);
 * dispatching through a sealed interface keeps the compressor body linear while the three
 * file-access layers stay isolated.
 */
private sealed interface ImageSource {

    fun size(): Long
    fun extension(): String
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

    override fun extension(): String = fileExtension(path)

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

    override fun extension(): String = fileExtension(uri.toString())

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

/**
 * In-memory `ByteArray` source — backs the CRA-95 Stream/Bytes short-circuit. Every read
 * operation opens a fresh [ByteArrayInputStream] against the same backing array so the five
 * [ImageSource] calls (header sniff, EXIF, bounds, decode) don't interfere with each other.
 *
 * No [fileExtension] is available — pass `""` to [detectInputImageFormat]; the magic-byte
 * sniffer picks up every format we support from the first 32 bytes, so the empty fallback
 * only affects DNG which has no magic bytes anyway (and isn't a realistic Stream input).
 */
private class ByteArrayImageSource(private val bytes: ByteArray) : ImageSource {

    override fun size(): Long = bytes.size.toLong()

    override fun extension(): String = ""

    override fun readHeader(): ByteArray {
        val take = minOf(IMAGE_SNIFF_BYTES, bytes.size)
        return bytes.copyOf(take)
    }

    override fun readExifRotation(): ExifRotation =
        ByteArrayInputStream(bytes).use { decodeExifOrientation(ExifInterface(it)) }

    override fun decodeRawDimensions(): ImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ImageCompressionError.DecodingFailed("Cannot decode image dimensions from in-memory bytes")
        }
        return ImageDimensions(options.outWidth, options.outHeight)
    }

    override fun decodeSampledBitmap(
        rawDims: ImageDimensions,
        target: ImageDimensions,
        exifRotation: ExifRotation,
    ): Bitmap {
        val options = buildSampledDecodeOptions(rawDims, target, exifRotation)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ImageCompressionError.DecodingFailed("Failed to decode image from in-memory bytes")
        return rotateOrRecycle(decoded, exifRotation)
    }
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
