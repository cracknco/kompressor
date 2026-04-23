/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    co.crackn.kompressor.ExperimentalKompressorApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)
// TooManyFunctions suppressed: CRA-95 added `compress(MediaSource, MediaDestination, ...)`
// plus the `shortCircuitBytesInput` / `shortCircuitStreamInput` / `shortCircuitNsDataInput`
// helpers that route through `UIImage(data:)` / `CGImageSource` without a temp-file hop.
// Splitting out would fragment the decode → transform → encode pipeline private to this file.
@file:Suppress("TooManyFunctions")

package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.io.IosDataMediaSource
import co.crackn.kompressor.io.IosPHAssetMediaSource
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.MediaType
import co.crackn.kompressor.io.PHAssetIcloudOnlyException
import co.crackn.kompressor.io.PHAssetResolutionException
import co.crackn.kompressor.io.resolveImageToData
import co.crackn.kompressor.io.toIosInputPath
import co.crackn.kompressor.io.toIosOutputHandle
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.logging.redactPath
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
import kotlin.coroutines.cancellation.CancellationException
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
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.writeToURL
import okio.buffer
import platform.Photos.PHAssetMediaTypeImage
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageDestinationRef
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIDevice
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/** iOS image compressor backed by [UIImage] and Core Graphics / ImageIO. */
// TooManyFunctions suppressed because the NSData fast-path requires a dedicated
// `instrumentCompressFromData` helper (CRA-47 observability parity with the file-path
// compress entry) which pushes the class one past the default threshold. Each helper
// remains narrowly scoped and single-purpose.
@Suppress("TooManyFunctions")
internal class IosImageCompressor(
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
) : ImageCompressor {

    // LongMethod suppressed: the nested try/finally structure (inHandle outer, outHandle inner)
    // is mandated by lifecycle cleanup correctness — see the inline comment on the file-path
    // dispatch branch. The NSData short-circuit path + PHAsset-image fast-path branches are
    // intrinsically inline at this dispatch layer; extracting them to helpers would add 2+
    // narrowly-scoped functions to this already-at-threshold class. Tradeoff is documented.
    @Suppress("LongMethod")
    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        try {
            // CRA-95: Stream / Bytes short-circuit — read the full payload into NSData and
            // route through the zero-copy NSData fast path. No temp file on the input side;
            // image payloads are bounded (~50 MB typical) so holding in RAM is safe and spares
            // the materialization round-trip that audio / video must pay.
            val streamOrBytesData: NSData? = when (input) {
                is MediaSource.Local.Bytes -> nsDataFromBytes(input.bytes)
                is MediaSource.Local.Stream -> nsDataFromStream(input)
                else -> null
            }

            // PHAsset-image fast path: resolve PhotoKit's NSData directly, skip temp-file
            // materialisation, and route through the zero-copy UIImage(data:) decode. Video /
            // audio PHAsset inputs still resolve via the URL path below — only image benefits
            // from the NSData shortcut [PR #142 review, finding #3].
            val effectiveInput: MediaSource = when {
                streamOrBytesData != null -> IosDataMediaSource(streamOrBytesData)
                input is IosPHAssetMediaSource && input.asset.mediaType == PHAssetMediaTypeImage ->
                    IosDataMediaSource(input.asset.resolveImageToData(input.allowNetworkAccess))
                else -> input
            }

            // NSData path short-circuits through UIImage(data:) — no temp file. UIImage's data
            // initialiser internally uses CGImageSourceCreateWithData, so the decode is
            // zero-copy when the NSData is mmap-backed (the typical case for PhotoKit /
            // `Data(contentsOf:)` inputs). For Stream / Bytes inputs we first copied into
            // NSData above; the decode itself is still no-temp-file.
            if (effectiveInput is IosDataMediaSource) {
                val outHandle = output.toIosOutputHandle()
                return@suspendRunCatching try {
                    val result = instrumentCompressFromData(effectiveInput.data, outHandle.tempPath, config)
                    // commit() copies temp → consumer sink for Stream destinations (no-op for
                    // FilePath / NSURL). Image has no progress callback, so pass the default.
                    outHandle.commit()
                    result
                } finally {
                    outHandle.cleanup()
                }
            }

            // Nested try/finally so `inHandle.cleanup()` runs even when `toIosOutputHandle()`
            // throws. Mirrors the Android sibling's lifecycle structure [PR #142 review,
            // finding #1]. `MediaType.IMAGE` skips the Bytes OOM warn in the resolver — the
            // short-circuit above ensures Bytes inputs never reach this dispatch branch anyway.
            val inHandle = effectiveInput.toIosInputPath(mediaType = MediaType.IMAGE, logger = logger)
            try {
                val outHandle = output.toIosOutputHandle()
                try {
                    // Route through the private `compressFilePath` helper — it wraps with
                    // `logger.instrumentCompress` so the CRA-47 start/success/failure log lines
                    // fire for every MediaSource/MediaDestination caller, matching the audio /
                    // video siblings.
                    val result = compressFilePath(inHandle.path, outHandle.tempPath, config)
                    outHandle.commit()
                    result
                } finally {
                    outHandle.cleanup()
                }
            } finally {
                inHandle.cleanup()
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

    // LongMethod suppressed for the same reason as the [compress] override: the nested
    // try/finally structure mirrors it line-for-line so the two entrypoints have identical
    // lifecycle semantics (inHandle cleanup outer, outHandle cleanup inner). The argument
    // validation + PHAsset/Stream/Bytes short-circuit + file-path fallback dispatch all live
    // inline at this layer; extracting them again would add helpers to an already-at-threshold
    // class without clarifying intent.
    @Suppress("LongMethod")
    override suspend fun thumbnail(
        input: MediaSource,
        output: MediaDestination,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): Result<CompressionResult> {
        // Fail-fast before entering suspendRunCatching so the IllegalArgumentException surfaces
        // as Result.failure without opening any input/output handles. Sibling contract to the
        // Android override; keeps the public API's validation behaviour symmetric.
        if (maxDimension <= 0) {
            return Result.failure(
                IllegalArgumentException("maxDimension must be > 0, was $maxDimension"),
            )
        }
        return suspendRunCatching {
            try {
                // Stream / Bytes short-circuit — mirror the compress() dispatch so thumbnail
                // inherits the CRA-95 "no temp file for image inputs" invariant.
                val streamOrBytesData: NSData? = when (input) {
                    is MediaSource.Local.Bytes -> nsDataFromBytes(input.bytes)
                    is MediaSource.Local.Stream -> nsDataFromStream(input)
                    else -> null
                }
                val effectiveInput: MediaSource = when {
                    streamOrBytesData != null -> IosDataMediaSource(streamOrBytesData)
                    input is IosPHAssetMediaSource && input.asset.mediaType == PHAssetMediaTypeImage ->
                        IosDataMediaSource(input.asset.resolveImageToData(input.allowNetworkAccess))
                    else -> input
                }

                // NSData path: CGImageSourceCreateWithData (zero-copy for mmap-backed NSData)
                // → CGImageSourceCreateThumbnailAtIndex, skipping the full-resolution decode
                // that compress() would do via UIImage(data:).
                if (effectiveInput is IosDataMediaSource) {
                    val outHandle = output.toIosOutputHandle()
                    return@suspendRunCatching try {
                        val result = thumbnailFromData(
                            effectiveInput.data, outHandle.tempPath, maxDimension, format, quality,
                        )
                        outHandle.commit()
                        result
                    } finally {
                        outHandle.cleanup()
                    }
                }

                val inHandle = effectiveInput.toIosInputPath(mediaType = MediaType.IMAGE, logger = logger)
                try {
                    val outHandle = output.toIosOutputHandle()
                    try {
                        val result = thumbnailFilePath(
                            inHandle.path, outHandle.tempPath, maxDimension, format, quality,
                        )
                        outHandle.commit()
                        result
                    } finally {
                        outHandle.cleanup()
                    }
                } finally {
                    inHandle.cleanup()
                }
            } catch (e: PHAssetIcloudOnlyException) {
                throw ImageCompressionError.SourceNotFound(e.message ?: "PHAsset iCloud-only", cause = e)
            } catch (e: PHAssetResolutionException) {
                throw ImageCompressionError.IoFailed(e.message ?: "PHAsset resolution failed", cause = e)
            }
        }
    }

    /**
     * Local-file → local-file compression with the CRA-47 [instrumentCompress] wrapper applied.
     * Throws the typed [ImageCompressionError] hierarchy on failure; the outer MediaSource
     * dispatch's `suspendRunCatching` converts those throws into `Result.failure`.
     */
    // LongMethod suppressed: the body is a single `instrumentCompress` call whose shape (tag +
    // three message builders) is mandated by CRA-47 observability. Splitting the message
    // builders out as private helpers only shifts line count around without clarifying intent.
    @Suppress("LongMethod")
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
            // Structured concurrency parity with AndroidImageCompressor: cancellation must
            // propagate to the calling scope as a CancellationException, not be wrapped as
            // ImageCompressionError.Unknown by the generic Throwable branch below.
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

    /**
     * Observability-wrapped variant of [doCompressFromData] for the NSData fast-path. Parallels
     * [compressFilePath]'s `instrumentCompress` wrap so the CRA-47 start/success/failure log lines
     * fire for in-memory data inputs too. Exception classification mirrors [compressFilePath]
     * line-for-line.
     */
    // LongMethod suppressed for the same reason as [compressFilePath] above: the body is a
    // single observability wrapper + the taxonomised exception table mandated by CRA-47.
    @Suppress("LongMethod")
    private suspend fun instrumentCompressFromData(
        data: NSData,
        outputPath: String,
        config: ImageCompressionConfig,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.IMAGE,
        startMessage = {
            "compress(data) start len=${data.length} out=${redactPath(outputPath)} " +
                "fmt=${config.format} quality=${config.quality} " +
                "max=${config.maxWidth}x${config.maxHeight} aspect=${config.keepAspectRatio}"
        },
        successMessage = { r ->
            "compress(data) ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
        },
        failureMessage = { "compress(data) failed len=${data.length}" },
    ) {
        try {
            doCompressFromData(data, outputPath, config)
        } catch (e: ImageCompressionError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: CancellationException) {
            // Same rationale as compressFilePath above — propagate cancellation verbatim.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: NullPointerException) {
            throw ImageCompressionError.DecodingFailed(
                "Platform decoder failed (nil image from NSData): ${e.message ?: "UIImage(data:)"}",
                e,
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            throw ImageCompressionError.Unknown(e.message ?: e::class.simpleName.orEmpty(), e)
        }
    }

    /**
     * File-path thumbnail entrypoint — wraps the CGImageSource flow with [instrumentCompress]
     * so the CRA-47 observability log lines fire for thumbnail calls too. Exception classification
     * mirrors [compressFilePath] so callers see identical typed errors across the two APIs.
     */
    @Suppress("LongMethod")
    private suspend fun thumbnailFilePath(
        inputPath: String,
        outputPath: String,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.IMAGE,
        startMessage = {
            "thumbnail() start in=${redactPath(inputPath)} out=${redactPath(outputPath)} " +
                "fmt=$format quality=$quality maxDim=$maxDimension"
        },
        successMessage = { r ->
            "thumbnail() ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
        },
        failureMessage = { "thumbnail() failed in=${redactPath(inputPath)}" },
    ) {
        try {
            doThumbnailFromPath(inputPath, outputPath, maxDimension, format, quality)
        } catch (e: ImageCompressionError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: NullPointerException) {
            throw ImageCompressionError.DecodingFailed(
                "Platform decoder failed (nil image): ${e.message ?: "CGImageSourceCreateWithURL"}",
                e,
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            throw ImageCompressionError.Unknown(e.message ?: e::class.simpleName.orEmpty(), e)
        }
    }

    /**
     * NSData thumbnail entrypoint — CGImageSource reads the samples directly from memory without
     * a temp-file roundtrip. Observability + exception table parity with [thumbnailFilePath].
     */
    @Suppress("LongMethod")
    private suspend fun thumbnailFromData(
        data: NSData,
        outputPath: String,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.IMAGE,
        startMessage = {
            "thumbnail(data) start len=${data.length} out=${redactPath(outputPath)} " +
                "fmt=$format quality=$quality maxDim=$maxDimension"
        },
        successMessage = { r ->
            "thumbnail(data) ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
        },
        failureMessage = { "thumbnail(data) failed len=${data.length}" },
    ) {
        try {
            doThumbnailFromData(data, outputPath, maxDimension, format, quality)
        } catch (e: ImageCompressionError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: NullPointerException) {
            throw ImageCompressionError.DecodingFailed(
                "Platform decoder failed (nil image from NSData): " +
                    "${e.message ?: "CGImageSourceCreateWithData"}",
                e,
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            throw ImageCompressionError.Unknown(e.message ?: e::class.simpleName.orEmpty(), e)
        }
    }

    /**
     * Sampled-decode path for a filesystem source. Creates a [CGImageSourceRef] pointed at the
     * file, asks ImageIO for a thumbnail no larger than [maxDimension] on its long edge, and
     * hands the resulting [platform.CoreGraphics.CGImageRef] to the shared [writeImage] encoder.
     * ImageIO applies EXIF rotation inside its thumbnail pipeline when
     * `kCGImageSourceCreateThumbnailWithTransform = true` so the caller never sees an un-rotated
     * bitmap — no second transform pass required.
     */
    @Suppress("USELESS_ELVIS")
    private suspend fun doThumbnailFromPath(
        inputPath: String,
        outputPath: String,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult {
        val iosVersion = iosMajorVersion()
        val startTime = CFAbsoluteTimeGetCurrent()
        val inputSize = nsFileSize(inputPath)

        // No explicit fileExistsAtPath pre-check — `readHeader` returns an empty ByteArray for
        // a missing/unreadable file (NSFileHandle fails to open), which `detectInputImageFormat`
        // then classifies. The real existence gate is `CGImageSourceCreateWithURL` below, which
        // returns null for a missing file and lets us throw `DecodingFailed` with the same error
        // shape. Avoids a TOCTOU window between fileExistsAtPath and the open.
        val detectedFormat = detectInputImageFormat(readHeader(inputPath), fileExtension(inputPath))
        throwIfIosIncompatible(format, detectedFormat, iosVersion)

        val urlCF = bridgeUrl(inputPath)
        val source: CGImageSourceRef = try {
            CGImageSourceCreateWithURL(urlCF, options = null)
                ?: throw ImageCompressionError.DecodingFailed("Cannot open CGImageSource for: $inputPath")
        } finally {
            CFRelease(urlCF)
        }
        currentCoroutineContext().ensureActive()
        try {
            val thumbnailImage = decodeThumbnailUIImage(source, maxDimension, "path=$inputPath")
            currentCoroutineContext().ensureActive()
            val thumbnailConfig = ImageCompressionConfig(format = format, quality = quality)
            writeImage(thumbnailImage, outputPath, thumbnailConfig)
        } finally {
            CFRelease(source)
        }

        val outputSize = nsFileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        return CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * NSData variant of [doThumbnailFromPath]. Uses `CGImageSourceCreateWithData` which is
     * zero-copy for mmap-backed NSData (typical of PhotoKit / `Data(contentsOf:)` inputs) —
     * the sampled decode itself still avoids loading the full-resolution bitmap into RAM.
     */
    // LongMethod suppressed: mirrors [doThumbnailFromPath] structure — iOS-version/gate
    // check, CFBridgingRetain lifecycle (CFRelease of dataCF after source creation, CFRelease
    // of source after decode), cancellation yield points, and the shared writeImage call. The
    // body is already minimal per step; extracting the CFDataRef dance into a helper would only
    // shift line count at the cost of an extra closure that obscures the retain/release pairing.
    @Suppress("USELESS_ELVIS", "UNCHECKED_CAST", "LongMethod")
    private suspend fun doThumbnailFromData(
        data: NSData,
        outputPath: String,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult {
        val iosVersion = iosMajorVersion()
        val startTime = CFAbsoluteTimeGetCurrent()
        val inputSize = data.length.toLong()

        val detectedFormat = detectInputImageFormat(readHeaderFromData(data), extension = "")
        throwIfIosIncompatible(format, detectedFormat, iosVersion)

        // NSData → CFDataRef via CFBridgingRetain. CGImageSource retains its own reference
        // for the lifetime of the source so we release ours immediately after construction;
        // the source stays valid for thumbnail decode below.
        val dataCF = CFBridgingRetain(data)
            ?: throw ImageCompressionError.DecodingFailed("Failed to bridge NSData to CFDataRef")
        val source: CGImageSourceRef = try {
            @Suppress("UNCHECKED_CAST")
            CGImageSourceCreateWithData(
                dataCF as platform.CoreFoundation.CFDataRef?,
                options = null,
            ) ?: throw ImageCompressionError.DecodingFailed(
                "Cannot open CGImageSource from NSData (length=${data.length})",
            )
        } finally {
            CFRelease(dataCF)
        }
        currentCoroutineContext().ensureActive()
        try {
            val thumbnailImage = decodeThumbnailUIImage(source, maxDimension, "len=${data.length}")
            currentCoroutineContext().ensureActive()
            val thumbnailConfig = ImageCompressionConfig(format = format, quality = quality)
            writeImage(thumbnailImage, outputPath, thumbnailConfig)
        } finally {
            CFRelease(source)
        }

        val outputSize = nsFileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        return CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Runs [CGImageSourceCreateThumbnailAtIndex] with options `ThumbnailFromImageAlways = true`,
     * `ThumbnailWithTransform = true` (so EXIF orientation is baked into the pixel data), and
     * `ThumbnailMaxPixelSize = maxDim`. Wraps the resulting [platform.CoreGraphics.CGImageRef]
     * in a [UIImage] so the shared [writeImage] encoder can reuse the JPEG / HEIC / AVIF writers
     * without a parallel code path.
     *
     * [diagnostic] is folded into the error message — the caller passes a path or a data length
     * so debug logs pinpoint the failing input without the helper needing to know which.
     */
    @Suppress("USELESS_ELVIS")
    private fun decodeThumbnailUIImage(
        source: CGImageSourceRef,
        maxDim: Int,
        diagnostic: String,
    ): UIImage = withThumbnailOptions(maxDim) { optionsCF ->
        val cgImage = CGImageSourceCreateThumbnailAtIndex(source, index = 0u, options = optionsCF)
            ?: throw ImageCompressionError.DecodingFailed(
                "CGImageSourceCreateThumbnailAtIndex returned null (maxDim=$maxDim, $diagnostic)",
            )
        try {
            UIImage(cGImage = cgImage)
        } finally {
            CFRelease(cgImage)
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

    private fun writeImage(image: UIImage, path: String, config: ImageCompressionConfig) =
        writeUIImageToFile(image, path, config.format, config.quality)

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
        const val SCALE_PIXELS = 1.0
    }
}

/**
 * Shared UIImage → file encoder used by [IosImageCompressor] **and** the video-thumbnail path
 * on [co.crackn.kompressor.video.IosVideoCompressor.thumbnail]. Both feed a [UIImage] (one
 * decoded from an image source, the other wrapped around a [platform.CoreGraphics.CGImageRef]
 * extracted by `AVAssetImageGenerator`) through the same encoder plumbing, so format/quality
 * handling stays in one place.
 *
 * Throws [ImageCompressionError.EncodingFailed] when the platform encoder returns nil/false,
 * or [ImageCompressionError.UnsupportedOutputFormat] when `CGImageDestinationCreateWithURL`
 * rejects the UTI (e.g. AVIF on iOS 15). The video caller catches the former and remaps to
 * [co.crackn.kompressor.video.VideoCompressionError.EncodingFailed]; the latter is remapped
 * to a typed video error upstream via [iosOutputGate] before reaching this function.
 */
internal fun writeUIImageToFile(
    image: UIImage,
    outputPath: String,
    format: ImageFormat,
    quality: Int,
) {
    when (format) {
        ImageFormat.JPEG -> writeJpeg(image, outputPath, quality)
        ImageFormat.HEIC -> writeViaImageIO(image, outputPath, quality, "public.heic", "heic")
        ImageFormat.AVIF -> writeViaImageIO(image, outputPath, quality, "public.avif", "avif")
        ImageFormat.WEBP -> error("WEBP output should be rejected by iosOutputGate before reaching here")
    }
}

private fun writeJpeg(image: UIImage, path: String, quality: Int) {
    val compressionQuality = quality.toDouble() / IOS_MAX_QUALITY
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
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
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
 * Builds the 3-entry thumbnail options CFDictionary and invokes [block] with it. Mirrors the
 * shape of [withQualityOptions] above — CFDictionaryCreate + CFRelease in a finally — but with
 * three entries: the integer `kCGImageSourceThumbnailMaxPixelSize`, and two boolean flags
 * (`kCGImageSourceCreateThumbnailFromImageAlways` = true forces sampled decode even when the
 * source has no embedded thumbnail at the requested size; `kCGImageSourceCreateThumbnailWithTransform`
 * = true bakes EXIF orientation into the pixel data so the caller never sees an un-rotated
 * bitmap). All CF references are released before [block] returns.
 */
// LongMethod suppressed: the body is a single CFDictionaryCreate with a 3-entry key/value
// setup, wrapped in the mandatory `memScoped { try { ... } finally { CFRelease } }` lifecycle
// boilerplate. Splitting out the key/value wiring or the dict finalize would only shuffle line
// count at the cost of breaking the retain/release pairing the compiler currently verifies by
// proximity. Mirrors the shape of [withQualityOptions] above.
@Suppress("UNCHECKED_CAST", "LongMethod")
private inline fun <R> withThumbnailOptions(maxDim: Int, block: (CFDictionaryRef?) -> R): R =
    memScoped {
        val maxDimVar = alloc<kotlinx.cinterop.IntVar>().apply { value = maxDim }
        val maxDimNumber: CFNumberRef = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, maxDimVar.ptr)
            ?: return@memScoped block(null)
        try {
            val keys = allocArray<COpaquePointerVar>(THUMBNAIL_OPTION_COUNT)
            val values = allocArray<COpaquePointerVar>(THUMBNAIL_OPTION_COUNT)
            keys[KEY_INDEX_MAX_PIXEL_SIZE] = kCGImageSourceThumbnailMaxPixelSize as COpaquePointer?
            values[KEY_INDEX_MAX_PIXEL_SIZE] = maxDimNumber as COpaquePointer
            keys[KEY_INDEX_CREATE_ALWAYS] = kCGImageSourceCreateThumbnailFromImageAlways as COpaquePointer?
            values[KEY_INDEX_CREATE_ALWAYS] = kCFBooleanTrue as COpaquePointer?
            keys[KEY_INDEX_WITH_TRANSFORM] = kCGImageSourceCreateThumbnailWithTransform as COpaquePointer?
            values[KEY_INDEX_WITH_TRANSFORM] = kCFBooleanTrue as COpaquePointer?
            val dict = CFDictionaryCreate(
                kCFAllocatorDefault,
                keys,
                values,
                THUMBNAIL_OPTION_COUNT.toLong(),
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )
            try {
                block(dict)
            } finally {
                if (dict != null) CFRelease(dict)
            }
        } finally {
            CFRelease(maxDimNumber)
        }
    }

private const val THUMBNAIL_OPTION_COUNT = 3
private const val KEY_INDEX_MAX_PIXEL_SIZE = 0
private const val KEY_INDEX_CREATE_ALWAYS = 1
private const val KEY_INDEX_WITH_TRANSFORM = 2

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
    // Read only IMAGE_SNIFF_BYTES — `NSData.dataWithContentsOfFile` would materialise the entire
    // file (15–20 MB for a 48 MP JPEG) into RAM just to copy the first 32 bytes, defeating the
    // memory-efficiency goal of the sampled-decode thumbnail path. NSFileHandle reads the exact
    // prefix length via an `lseek`+`read` under the hood.
    val handle: NSFileHandle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0)
    return try {
        val data: NSData = handle.readDataOfLength(IMAGE_SNIFF_BYTES.toULong())
        copyPrefixBytes(data)
    } finally {
        handle.closeFile()
    }
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

internal fun iosMajorVersion(): Int {
    val major = UIDevice.currentDevice.systemVersion.substringBefore('.')
    return major.toIntOrNull() ?: 0
}

private fun minVersionForUti(uti: String): Int = when (uti) {
    "public.heic" -> HEIC_OUTPUT_MIN_IOS
    "public.avif" -> AVIF_OUTPUT_MIN_IOS
    else -> 0
}

private const val IOS_MAX_QUALITY = 100.0

/**
 * Wrap a Kotlin [ByteArray] in an [NSData] that owns a freshly copied buffer. Required by the
 * CRA-95 Stream / Bytes → NSData short-circuit in the image compressor: `UIImage(data:)` only
 * accepts NSData, so the ByteArray (from okio / [MediaSource.Local.Bytes]) must hop through
 * `NSData.dataWithBytes:length:` (which copies on construction — the pinned pointer is safe to
 * release after [NSData.create] returns).
 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
private fun nsDataFromBytes(bytes: ByteArray): NSData =
    if (bytes.isEmpty()) {
        // `usePinned { addressOf(0) }` throws on a zero-length array; go through the nullable
        // bytes factory instead. `UIImage(data: zeroLengthData)` then surfaces the expected
        // DecodingFailed downstream.
        NSData.create(bytes = null, length = 0uL)
    } else {
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    }

/**
 * Read a [MediaSource.Local.Stream] fully into an [NSData], honouring
 * [MediaSource.Local.Stream.closeOnFinish]. Used by the CRA-95 Stream short-circuit in the
 * image compressor — images are bounded so the full-buffer read is the intended "no temp file"
 * path, and the resulting NSData hops straight into `UIImage(data:)`.
 */
private fun nsDataFromStream(stream: MediaSource.Local.Stream): NSData {
    val bufferedSource = stream.source.buffer()
    val bytes = try {
        bufferedSource.readByteArray()
    } finally {
        if (stream.closeOnFinish) {
            // Closing the buffered wrapper closes the delegate source too.
            runCatching { bufferedSource.close() }
        }
    }
    return nsDataFromBytes(bytes)
}
