package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/** iOS image compressor backed by [UIImage] and Core Graphics. */
@OptIn(ExperimentalForeignApi::class)
internal class IosImageCompressor : ImageCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        require(config.format == ImageFormat.JPEG) { "Only JPEG format is currently supported" }
        val startTime = CFAbsoluteTimeGetCurrent()
        onProgress(0f)

        val inputSize = fileSize(inputPath)
        val image = loadImage(inputPath)
        currentCoroutineContext().ensureActive()
        onProgress(0.3f)

        val (pixelWidth, pixelHeight) = orientedPixelDimensions(image)
        val target = calculateTargetDimensions(
            pixelWidth, pixelHeight,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val resized = resizeImageIfNeeded(image, pixelWidth, pixelHeight, target)
        currentCoroutineContext().ensureActive()
        onProgress(0.6f)

        writeJpeg(resized, outputPath, config.quality)
        onProgress(1f)

        val outputSize = fileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()

        CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Returns pixel dimensions that respect the EXIF/UIImage orientation.
     * [UIImage.size] returns points in the display orientation;
     * multiplying by [UIImage.scale] gives the actual pixel count.
     */
    private fun orientedPixelDimensions(image: UIImage): Pair<Int, Int> {
        val size = image.size.useContents { Pair(width, height) }
        val scale = image.scale
        return Pair(
            (size.first * scale).toInt(),
            (size.second * scale).toInt(),
        )
    }

    private fun loadImage(path: String): UIImage =
        UIImage(contentsOfFile = path)

    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?: error("File not found: $path")
        return (attrs[NSFileSize] as? Number)?.toLong()
            ?: error("Cannot read file size: $path")
    }

    private fun resizeImageIfNeeded(
        image: UIImage,
        origWidth: Int,
        origHeight: Int,
        target: ImageDimensions,
    ): UIImage = if (origWidth == target.width && origHeight == target.height) {
        // No resize needed — flatten orientation by redrawing at original size and scale
        val size = image.size.useContents { Pair(width, height) }
        redrawInContext(image, size.first, size.second, image.scale)
    } else {
        redrawInContext(image, target.width.toDouble(), target.height.toDouble(), SCALE_PIXELS)
    }

    /** Draws [image] into a new bitmap context, flattening its orientation transform. */
    private fun redrawInContext(image: UIImage, width: Double, height: Double, scale: Double): UIImage {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), true, scale)
        try {
            image.drawInRect(CGRectMake(0.0, 0.0, width, height))
            return UIGraphicsGetImageFromCurrentImageContext()
                ?: error("Failed to draw image into context")
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun writeJpeg(image: UIImage, path: String, quality: Int) {
        val compressionQuality = quality.toDouble() / MAX_QUALITY
        val data = UIImageJPEGRepresentation(image, compressionQuality)
            ?: error("Failed to create JPEG data")
        val url = NSURL.fileURLWithPath(path)
        val written = data.writeToURL(url, atomically = true)
        check(written) { "Failed to write JPEG to: $path (quality=$quality)" }
    }

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
        const val MAX_QUALITY = 100.0
        const val SCALE_PIXELS = 1.0
    }
}
