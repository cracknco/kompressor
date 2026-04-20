/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    co.crackn.kompressor.ExperimentalKompressorApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageDestinationRef
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.UIKit.UIDevice
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/** iOS image compressor backed by [UIImage] and Core Graphics / ImageIO. */
internal class IosImageCompressor(
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
) : ImageCompressor {

    // LongMethod suppressed: the body is a single `instrumentCompress` call whose shape (tag +
    // three message builders) is mandated by CRA-47 observability. Splitting the message
    // builders out as private helpers only shifts line count around without clarifying intent,
    // since each one is scoped to compress() and has no other caller.
    @Suppress("LongMethod")
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
    }

    private suspend fun doCompress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val iosVersion = iosMajorVersion()
        iosOutputGate(config.format, iosVersion)?.let { throw it }

        val startTime = CFAbsoluteTimeGetCurrent()

        val inputSize = nsFileSize(inputPath)
        val detectedFormat = detectInputImageFormat(readHeader(inputPath), fileExtension(inputPath))
        iosInputGate(detectedFormat, iosVersion)?.let { throw it }

        val image = loadImage(inputPath)
        currentCoroutineContext().ensureActive()

        val (pixelWidth, pixelHeight) = orientedPixelDimensions(image)
        val target = calculateTargetDimensions(
            pixelWidth, pixelHeight,
            config.maxWidth, config.maxHeight, config.keepAspectRatio,
        )
        val resized = resizeImageIfNeeded(image, pixelWidth, pixelHeight, target)
        currentCoroutineContext().ensureActive()

        writeImage(resized, outputPath, config)

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

    private fun writeImage(image: UIImage, path: String, config: ImageCompressionConfig) = when (config.format) {
        ImageFormat.JPEG -> writeJpeg(image, path, config.quality)
        ImageFormat.HEIC -> writeViaImageIO(image, path, config.quality, UTI_HEIC, "heic")
        ImageFormat.AVIF -> writeViaImageIO(image, path, config.quality, UTI_AVIF, "avif")
        ImageFormat.WEBP -> error("WEBP output should be rejected by iosOutputGate before reaching here")
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

    /**
     * Encodes [image] to [path] via ImageIO using the UTType [uti]. Used for HEIC and AVIF —
     * both are ISOBMFF containers that Apple exposes through the same `CGImageDestination` API;
     * only the destination UTI differs. When the platform lacks an encoder for [uti] (iOS < 16
     * for AVIF on most devices), `CGImageDestinationCreateWithURL` returns `null`; we map that
     * back to a typed [ImageCompressionError.UnsupportedOutputFormat] rather than letting the
     * caller hit the generic `EncodingFailed` branch.
     */
    private fun writeViaImageIO(image: UIImage, path: String, quality: Int, uti: String, formatId: String) {
        val cgImage = image.CGImage
            ?: throw ImageCompressionError.EncodingFailed("UIImage has no backing CGImage for $formatId")
        val utiCF = bridgeUti(uti)
        val urlCF = bridgeUrl(path)
        try {
            val destination = CGImageDestinationCreateWithURL(urlCF, utiCF, count = 1u, options = null)
                ?: throw ImageCompressionError.UnsupportedOutputFormat(
                    format = formatId,
                    platform = PLATFORM_IOS,
                    minApi = minVersionForUti(uti),
                )
            try {
                finalizeImageIODestination(destination, cgImage, quality, formatId, path)
            } finally {
                CFRelease(destination)
            }
        } finally {
            CFRelease(utiCF)
            CFRelease(urlCF)
        }
    }

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
        const val MAX_QUALITY = 100.0
        const val SCALE_PIXELS = 1.0
        const val UTI_HEIC = "public.heic"
        const val UTI_AVIF = "public.avif"
    }
}

@Suppress("UNCHECKED_CAST")
private fun bridgeUti(uti: String): CFStringRef =
    CFBridgingRetain(uti) as CFStringRef?
        ?: throw ImageCompressionError.EncodingFailed("Failed to bridge UTI $uti")

@Suppress("UNCHECKED_CAST")
private fun bridgeUrl(path: String): CFURLRef =
    CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef?
        ?: throw ImageCompressionError.EncodingFailed("Failed to bridge URL $path")

/** Adds [cgImage] with quality metadata and finalises; maps finalize=false to EncodingFailed. */
private fun finalizeImageIODestination(
    destination: CGImageDestinationRef,
    cgImage: CGImageRef,
    quality: Int,
    formatId: String,
    path: String,
) {
    val finalised = withQualityOptions(quality) { optionsCF ->
        CGImageDestinationAddImage(destination, cgImage, optionsCF)
        CGImageDestinationFinalize(destination)
    }
    if (!finalised) {
        throw ImageCompressionError.EncodingFailed(
            "CGImageDestinationFinalize returned false for $formatId at $path",
        )
    }
}

/**
 * Builds a per-image `CFDictionary` with one entry — `kCGImageDestinationLossyCompressionQuality`
 * mapped to the caller's quality normalised to `[0, 1]` — and invokes [block] with it. ImageIO is
 * a Core Foundation API and expects CF types; building the dictionary via `CFDictionaryCreate`
 * avoids the Obj-C `NSCopying` key-constraint that makes a raw Kotlin-`String` key impossible to
 * pass through `NSDictionary.dictionaryWithObject(forKey:)`. All temporary CF references are
 * released before [block] returns.
 */
private inline fun <R> withQualityOptions(quality: Int, block: (CFDictionaryRef?) -> R): R {
    val q = quality.toDouble() / IOS_MAX_QUALITY
    return memScoped {
        val qVar = alloc<DoubleVar>().apply { value = q }
        val numberRef: CFNumberRef = CFNumberCreate(kCFAllocatorDefault, kCFNumberDoubleType, qVar.ptr)
            ?: return@memScoped block(null)
        try {
            val keys = allocArray<COpaquePointerVar>(1)
            val values = allocArray<COpaquePointerVar>(1)
            keys[0] = kCGImageDestinationLossyCompressionQuality as COpaquePointer?
            values[0] = numberRef as COpaquePointer
            val dict = CFDictionaryCreate(
                kCFAllocatorDefault,
                keys,
                values,
                1,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )
            try {
                block(dict)
            } finally {
                if (dict != null) CFRelease(dict)
            }
        } finally {
            CFRelease(numberRef)
        }
    }
}

private fun readHeader(path: String): ByteArray {
    val data: NSData = NSData.dataWithContentsOfFile(path) ?: return ByteArray(0)
    val size = minOf(IMAGE_SNIFF_BYTES, data.length.toInt())
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, size.toULong())
        }
    }
    return bytes
}

private fun iosMajorVersion(): Int {
    val major = UIDevice.currentDevice.systemVersion.substringBefore('.')
    return major.toIntOrNull() ?: 0
}

private fun minVersionForUti(uti: String): Int = when (uti) {
    "public.heic" -> HEIC_OUTPUT_MIN_IOS
    "public.avif" -> AVIF_OUTPUT_MIN_IOS
    else -> 0
}

private const val IOS_MAX_QUALITY = 100.0
