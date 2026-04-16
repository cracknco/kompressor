/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.Foundation.NSFileManager

/** iOS image compressor backed by [UIImage] and Core Graphics. */
@OptIn(ExperimentalForeignApi::class)
internal class IosImageCompressor : ImageCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        require(config.format == ImageFormat.JPEG) { "Only JPEG format is currently supported" }
        try {
            doCompress(inputPath, outputPath, config)
        } catch (e: ImageCompressionError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: NullPointerException) {
            // Kotlin/Native wraps Obj-C `nil` returns as non-null bindings, so a malformed
            // JPEG that `UIImage(contentsOfFile=)` can't decode surfaces as an NPE when the
            // caller subsequently dereferences a member. Classify it as `DecodingFailed` so
            // callers see the same typed error as the Android side.
            throw ImageCompressionError.DecodingFailed(
                "Platform decoder failed (nil image): ${e.message ?: "UIImage(contentsOfFile)"}",
                e,
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            throw ImageCompressionError.Unknown(e.message ?: e::class.simpleName.orEmpty(), e)
        }
    }

    private suspend fun doCompress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val startTime = CFAbsoluteTimeGetCurrent()

        val inputSize = nsFileSize(inputPath)
        val image = loadImage(inputPath)
        currentCoroutineContext().ensureActive()

        val (pixelWidth, pixelHeight) = orientedPixelDimensions(image)
        val target = calculateTargetDimensions(
            pixelWidth, pixelHeight,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val resized = resizeImageIfNeeded(image, pixelWidth, pixelHeight, target)
        currentCoroutineContext().ensureActive()

        writeJpeg(resized, outputPath, config.quality)

        val outputSize = nsFileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()

        return CompressionResult(inputSize, outputSize, durationMs)
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

    @Suppress("USELESS_ELVIS")
    private fun loadImage(path: String): UIImage {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            throw ImageCompressionError.IoFailed("Input file not found: $path")
        }
        return UIImage(contentsOfFile = path)
            ?: throw ImageCompressionError.DecodingFailed("Failed to decode image: $path")
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
                ?: throw ImageCompressionError.EncodingFailed("Failed to draw image into context")
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun writeJpeg(image: UIImage, path: String, quality: Int) {
        val compressionQuality = quality.toDouble() / MAX_QUALITY
        val data = UIImageJPEGRepresentation(image, compressionQuality)
            ?: throw ImageCompressionError.EncodingFailed("UIImageJPEGRepresentation returned nil")
        val url = NSURL.fileURLWithPath(path)
        val written = data.writeToURL(url, atomically = true)
        if (!written) {
            throw ImageCompressionError.EncodingFailed(
                "Failed to write JPEG to: $path (quality=$quality)",
            )
        }
    }

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
        const val MAX_QUALITY = 100.0
        const val SCALE_PIXELS = 1.0
    }
}
