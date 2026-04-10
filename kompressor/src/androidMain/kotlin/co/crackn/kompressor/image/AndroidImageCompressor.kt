package co.crackn.kompressor.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        val originalDims = decodeImageDimensions(inputPath)
        val inputSize = File(inputPath).length()
        currentCoroutineContext().ensureActive()
        onProgress(0.1f)

        val target = calculateTargetDimensions(
            originalDims.width, originalDims.height,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val bitmap = decodeSampledBitmap(inputPath, originalDims, target)
        currentCoroutineContext().ensureActive()
        onProgress(0.3f)

        resizeAndWrite(bitmap, target, outputPath, config.quality)
        onProgress(1f)

        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

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

    private fun decodeImageDimensions(path: String): ImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        require(options.outWidth > 0 && options.outHeight > 0) {
            "Cannot decode image dimensions: $path"
        }
        return ImageDimensions(options.outWidth, options.outHeight)
    }

    private fun decodeSampledBitmap(path: String, original: ImageDimensions, target: ImageDimensions): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                original.width, original.height, target.width, target.height,
            )
        }
        return BitmapFactory.decodeFile(path, options)
            ?: error("Failed to decode image: $path")
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, target: ImageDimensions): Bitmap {
        if (bitmap.width == target.width && bitmap.height == target.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
    }

    private fun writeBitmapAsJpeg(bitmap: Bitmap, outputPath: String, quality: Int) {
        FileOutputStream(outputPath).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
