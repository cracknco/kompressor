package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
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
        val cgImage = image.CGImage ?: error("Cannot decode image: $inputPath")
        currentCoroutineContext().ensureActive()
        onProgress(0.3f)

        val origWidth = CGImageGetWidth(cgImage).toInt()
        val origHeight = CGImageGetHeight(cgImage).toInt()
        val target = calculateTargetDimensions(
            origWidth, origHeight,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val resized = resizeImageIfNeeded(image, origWidth, origHeight, target)
        currentCoroutineContext().ensureActive()
        onProgress(0.6f)

        writeJpeg(resized, outputPath, config.quality)
        onProgress(1f)

        val outputSize = fileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()

        CompressionResult(inputSize, outputSize, durationMs)
    }

    private fun loadImage(path: String): UIImage {
        return UIImage(contentsOfFile = path)
    }

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
    ): UIImage {
        if (origWidth == target.width && origHeight == target.height) return image

        val targetSize = CGSizeMake(target.width.toDouble(), target.height.toDouble())
        UIGraphicsBeginImageContextWithOptions(targetSize, true, SCALE_PIXELS)
        try {
            image.drawInRect(CGRectMake(0.0, 0.0, target.width.toDouble(), target.height.toDouble()))
            return UIGraphicsGetImageFromCurrentImageContext()
                ?: error("Failed to resize image")
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
