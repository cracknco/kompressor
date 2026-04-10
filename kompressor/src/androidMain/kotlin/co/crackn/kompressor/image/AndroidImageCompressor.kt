package co.crackn.kompressor.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.suspendRunCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

/** Android image compressor backed by [BitmapFactory] and [Bitmap.compress]. */
internal class AndroidImageCompressor : ImageCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        require(config.format == ImageFormat.JPEG) { "Only JPEG format is currently supported" }
        val startNanos = System.nanoTime()
        onProgress(0f)

        val inputSize = File(inputPath).length()
        val exifRotation = readExifRotation(inputPath)
        val rawDims = decodeRawDimensions(inputPath)
        val orientedDims = applyRotationToDimensions(rawDims, exifRotation)
        currentCoroutineContext().ensureActive()
        onProgress(0.1f)

        val target = calculateTargetDimensions(
            orientedDims.width, orientedDims.height,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val bitmap = decodeSampledBitmap(inputPath, rawDims, target, exifRotation)
        currentCoroutineContext().ensureActive()
        onProgress(0.3f)

        resizeAndWrite(bitmap, target, outputPath, config.quality)
        onProgress(1f)

        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    private fun readExifRotation(path: String): ExifRotation {
        val exif = ExifInterface(path)
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> ExifRotation(degrees = 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> ExifRotation(degrees = 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> ExifRotation(degrees = 270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifRotation(scaleX = -1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifRotation(scaleY = -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> ExifRotation(degrees = 90f, scaleX = -1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> ExifRotation(degrees = 270f, scaleX = -1f)
            else -> ExifRotation()
        }
    }

    private fun applyRotationToDimensions(dims: ImageDimensions, rotation: ExifRotation): ImageDimensions =
        if (rotation.swapsDimensions) ImageDimensions(dims.height, dims.width) else dims

    private fun resizeAndWrite(bitmap: Bitmap, target: ImageDimensions, outputPath: String, quality: Int) {
        try {
            val scaled = resizeBitmapIfNeeded(bitmap, target)
            try {
                writeBitmapAsJpeg(scaled, outputPath, quality)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeRawDimensions(path: String): ImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        require(options.outWidth > 0 && options.outHeight > 0) {
            "Cannot decode image dimensions: $path"
        }
        return ImageDimensions(options.outWidth, options.outHeight)
    }

    private fun decodeSampledBitmap(
        path: String,
        rawDims: ImageDimensions,
        target: ImageDimensions,
        exifRotation: ExifRotation,
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(rawDims.width, rawDims.height, target.width, target.height)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: error("Failed to decode image: $path")
        return try {
            applyExifRotation(decoded, exifRotation)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            decoded.recycle()
            throw e
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, rotation: ExifRotation): Bitmap {
        if (rotation.isIdentity) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotation.degrees)
            postScale(rotation.scaleX, rotation.scaleY)
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, target: ImageDimensions): Bitmap {
        if (bitmap.width == target.width && bitmap.height == target.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
    }

    private fun writeBitmapAsJpeg(bitmap: Bitmap, outputPath: String, quality: Int) {
        FileOutputStream(outputPath).use { stream ->
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            check(success) { "Failed to compress bitmap as JPEG to: $outputPath" }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

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
