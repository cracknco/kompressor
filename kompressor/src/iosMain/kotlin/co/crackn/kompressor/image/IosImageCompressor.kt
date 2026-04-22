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
import co.crackn.kompressor.io.IosDataMediaSource
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.PHAssetIcloudOnlyException
import co.crackn.kompressor.io.PHAssetResolutionException
import co.crackn.kompressor.io.toIosInputPath
import co.crackn.kompressor.io.toIosOutputHandle
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

    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        try {
            // NSData path short-circuits through UIImage(data:) — no temp file. UIImage's
            // data initialiser internally uses CGImageSourceCreateWithData, so the decode is
            // zero-copy when the NSData is mmap-backed (the typical case for PhotoKit /
            // `Data(contentsOf:)` inputs). Video / audio NSData is rejected by the dispatch
            // helper further down with a CRA-95-labelled error.
            if (input is IosDataMediaSource) {
                val outHandle = output.toIosOutputHandle()
                return@suspendRunCatching try {
                    val result = doCompressFromData(input.data, outHandle.tempPath, config)
                    outHandle.commit()
                    result
                } finally {
                    outHandle.cleanup()
                }
            }
            val inHandle = input.toIosInputPath()
            val outHandle = output.toIosOutputHandle()
            try {
                val result = doCompress(inHandle.path, outHandle.tempPath, config)
                outHandle.commit()
                result
            } finally {
                inHandle.cleanup()
                outHandle.cleanup()
            }
        } catch (e: PHAssetIcloudOnlyException) {
            // Translate the resolver's typed iCloud error into the image-specific typed error
            // so callers across image / audio / video see a consistent `SourceNotFound` subtype.
            // Chain `e` as the cause so the PhotoKit error code / message survives in stack traces.
            throw ImageCompressionError.SourceNotFound(e.message ?: "PHAsset iCloud-only", cause = e)
        } catch (e: PHAssetResolutionException) {
            throw ImageCompressionError.IoFailed(e.message ?: "PHAsset resolution failed", cause = e)
        }
    }

    private suspend fun doCompress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val iosVersion = iosMajorVersion()
        val startTime = CFAbsoluteTimeGetCurrent()

        val inputSize = nsFileSize(inputPath)
        val detectedFormat = detectInputImageFormat(readHeader(inputPath), fileExtension(inputPath))
        throwIfIosIncompatible(config.format, detectedFormat, iosVersion)

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
     * Variant of [doCompress] that decodes directly from an in-memory [NSData] buffer, skipping
     * the temp-file materialisation that would otherwise round-trip the bytes through disk.
     *
     * Format detection reads the first [IMAGE_SNIFF_BYTES][co.crackn.kompressor.image.IMAGE_SNIFF_BYTES]
     * from the buffer's raw pointer — same magic-byte table as the file path, minus the filename
     * extension fallback (the caller did not hand us a filename). DNG input will therefore fail
     * detection here; callers with DNG NSData need to write to a temp `.dng` file first.
     */
    @Suppress("USELESS_ELVIS")
    private suspend fun doCompressFromData(
        data: NSData,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult {
        val iosVersion = iosMajorVersion()
        val startTime = CFAbsoluteTimeGetCurrent()
        val inputSize = data.length.toLong()

        val detectedFormat = detectInputImageFormat(readHeaderFromData(data), extension = "")
        throwIfIosIncompatible(config.format, detectedFormat, iosVersion)

        // K/N types `UIImage(data:)` as non-null but the underlying Obj-C initialiser returns
        // nil when the data doesn't represent a decodable image. Mirror the `@Suppress` on
        // `loadImage(path)` so a malformed NSData still surfaces as `DecodingFailed` rather
        // than crashing downstream with an NPE on the first member access.
        val image = UIImage(data = data)
            ?: throw ImageCompressionError.DecodingFailed("Failed to decode NSData (length=${data.length})")
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
    ): UIImage {
        val (width, height, scale) = if (origWidth == target.width && origHeight == target.height) {
            // No resize needed — flatten orientation by redrawing at original size and scale.
            val size = image.size.useContents { Pair(width, height) }
            Triple(size.first, size.second, image.scale)
        } else {
            Triple(target.width.toDouble(), target.height.toDouble(), SCALE_PIXELS)
        }
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

/**
 * File-scope gate that consolidates the input + output iOS-version checks into a single
 * throw point. Pulled out of the two `doCompress*` variants so the per-variant throw count
 * stays under detekt's ceiling and the gate logic itself lives in one place — in particular
 * a future third decode path (e.g. PHAsset zero-copy) picks up the same checks for free.
 */
private fun throwIfIosIncompatible(
    outputFormat: ImageFormat,
    detectedFormat: InputImageFormat,
    iosVersion: Int,
) {
    iosOutputGate(outputFormat, iosVersion)?.let { throw it }
    iosInputGate(detectedFormat, iosVersion)?.let { throw it }
}

private fun readHeader(path: String): ByteArray {
    val data: NSData = NSData.dataWithContentsOfFile(path) ?: return ByteArray(0)
    return copyPrefixBytes(data)
}

/**
 * Variant of [readHeader] that sniffs the magic-byte header directly from an in-memory
 * [NSData] — used by the [IosDataMediaSource] input shortcut to avoid a temp-file roundtrip.
 */
private fun readHeaderFromData(data: NSData): ByteArray = copyPrefixBytes(data)

private fun copyPrefixBytes(data: NSData): ByteArray {
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
