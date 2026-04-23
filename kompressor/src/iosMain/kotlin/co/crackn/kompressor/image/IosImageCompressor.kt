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
import platform.Foundation.create
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

    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig,
    ): Result<CompressionResult> = suspendRunCatching {
        withPhAssetExceptionTranslation {
            dispatchFromInput(
                input = input,
                output = output,
                onData = { data, tempPath -> instrumentCompressFromData(data, tempPath, config) },
                onPath = { inPath, tempPath -> compressFilePath(inPath, tempPath, config) },
            )
        }
    }

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
            withPhAssetExceptionTranslation {
                dispatchFromInput(
                    input = input,
                    output = output,
                    onData = { data, tempPath -> thumbnailFromData(data, tempPath, maxDimension, format, quality) },
                    onPath = { inPath, tempPath -> thumbnailFilePath(inPath, tempPath, maxDimension, format, quality) },
                )
            }
        }
    }

    /**
     * Shared input-dispatch skeleton for [compress] / [thumbnail]. Normalises the [MediaSource] to
     * either an [IosDataMediaSource] (NSData short-circuit — Stream / Bytes / PHAsset-image all
     * collapse into this) or a file-path handle, then routes through [onData] or [onPath]
     * respectively while owning the `try { ... } finally { cleanup() }` lifecycle for every
     * handle the dispatch opens. Mirrors the Android sibling's two-tier try/finally so a throw in
     * the output handle's `toIosOutputHandle()` still runs the input handle's cleanup.
     *
     * The NSData short-circuit avoids a temp file on the input side: PhotoKit's `resolveImageToData`
     * returns an mmap-backed `NSData`, and `UIImage(data:)` / `CGImageSourceCreateWithData` decode
     * zero-copy from it. Stream / Bytes inputs first hop through `nsDataFromStream` / `nsDataFromBytes`
     * to materialise into NSData (image payloads are bounded so the in-RAM hop is the intended
     * "no temp file" path per CRA-95).
     */
    private suspend fun dispatchFromInput(
        input: MediaSource,
        output: MediaDestination,
        onData: suspend (NSData, String) -> CompressionResult,
        onPath: suspend (String, String) -> CompressionResult,
    ): CompressionResult {
        val effectiveInput = normaliseInputToNsDataOrPassthrough(input)
        return if (effectiveInput is IosDataMediaSource) {
            dispatchWithOutputHandle(output) { tempPath -> onData(effectiveInput.data, tempPath) }
        } else {
            val inHandle = effectiveInput.toIosInputPath(mediaType = MediaType.IMAGE, logger = logger)
            try {
                dispatchWithOutputHandle(output) { tempPath -> onPath(inHandle.path, tempPath) }
            } finally {
                inHandle.cleanup()
            }
        }
    }

    /**
     * Opens an output handle via [toIosOutputHandle], runs [block] with the `tempPath`, commits
     * the handle on success, and always cleans up in a `finally`. Extracted out of the per-entry
     * dispatch so `compress` + `thumbnail` share one `try/finally/commit/cleanup` block instead
     * of inlining it twice per variant.
     */
    private suspend fun dispatchWithOutputHandle(
        output: MediaDestination,
        block: suspend (String) -> CompressionResult,
    ): CompressionResult {
        val outHandle = output.toIosOutputHandle()
        return try {
            val result = block(outHandle.tempPath)
            outHandle.commit()
            result
        } finally {
            outHandle.cleanup()
        }
    }

    /**
     * Collapses Stream / Bytes / PHAsset-image inputs into an [IosDataMediaSource] so the
     * downstream dispatch has one branch to pattern-match against; any other [MediaSource]
     * subtype passes through unchanged. Isolates the CRA-95 + CRA-94 input normalisation from
     * the per-entry-point dispatch skeleton.
     */
    private fun normaliseInputToNsDataOrPassthrough(input: MediaSource): MediaSource {
        val streamOrBytesData: NSData? = when (input) {
            is MediaSource.Local.Bytes -> nsDataFromBytes(input.bytes)
            is MediaSource.Local.Stream -> nsDataFromStream(input)
            else -> null
        }
        return when {
            streamOrBytesData != null -> IosDataMediaSource(streamOrBytesData)
            input is IosPHAssetMediaSource && input.asset.mediaType == PHAssetMediaTypeImage ->
                IosDataMediaSource(input.asset.resolveImageToData(input.allowNetworkAccess))
            else -> input
        }
    }

    /**
     * Runs [block] with the two `PHAsset*` iCloud / resolution exceptions translated into the
     * image-specific typed error taxonomy — `SourceNotFound` for iCloud-only / missing PHAssets,
     * `IoFailed` for generic PhotoKit resolution failures. Mirrors the audio / video siblings so
     * callers see a consistent subtype regardless of modality.
     */
    private suspend inline fun <T> withPhAssetExceptionTranslation(block: () -> T): T = try {
        block()
    } catch (e: PHAssetIcloudOnlyException) {
        throw ImageCompressionError.SourceNotFound(e.message ?: "PHAsset iCloud-only", cause = e)
    } catch (e: PHAssetResolutionException) {
        throw ImageCompressionError.IoFailed(e.message ?: "PHAsset resolution failed", cause = e)
    }

    /**
     * Local-file → local-file compression with the CRA-47 [instrumentCompress] wrapper applied.
     * Throws the typed [ImageCompressionError] hierarchy on failure; the outer MediaSource
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
        runClassifyingIosImageErrors(nilDecoderHint = "UIImage(contentsOfFile)") {
            doCompress(inputPath, outputPath, config)
        }
    }

    /**
     * Observability-wrapped variant of [doCompressFromData] for the NSData fast-path. Parallels
     * [compressFilePath]'s `instrumentCompress` wrap so the CRA-47 start/success/failure log lines
     * fire for in-memory data inputs too. Exception classification mirrors [compressFilePath]
     * line-for-line via [runClassifyingIosImageErrors].
     */
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
        runClassifyingIosImageErrors(nilDecoderHint = "UIImage(data:)") {
            doCompressFromData(data, outputPath, config)
        }
    }

    /**
     * File-path thumbnail entrypoint — wraps the CGImageSource flow with [instrumentCompress]
     * so the CRA-47 observability log lines fire for thumbnail calls too. Exception classification
     * mirrors [compressFilePath] via the shared [runClassifyingIosImageErrors] helper.
     */
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
        runClassifyingIosImageErrors(nilDecoderHint = "CGImageSourceCreateWithURL") {
            doThumbnailFromPath(inputPath, outputPath, maxDimension, format, quality)
        }
    }

    /**
     * NSData thumbnail entrypoint — CGImageSource reads the samples directly from memory without
     * a temp-file roundtrip. Observability + exception table parity with [thumbnailFilePath].
     */
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
        runClassifyingIosImageErrors(nilDecoderHint = "CGImageSourceCreateWithData") {
            doThumbnailFromData(data, outputPath, maxDimension, format, quality)
        }
    }

    /**
     * Shared exception-classification ladder for the four `instrumentCompress`-wrapped entry
     * points. Kotlin/Native wraps Obj-C `nil` returns as non-null bindings, so a malformed input
     * that `UIImage(...)` / `CGImageSourceCreateWithURL` can't decode surfaces as a downstream
     * [NullPointerException]; classify it as [ImageCompressionError.DecodingFailed] for parity
     * with the Android `classifyAndroidImageError` taxonomy. [CancellationException] is
     * re-thrown verbatim so structured concurrency unwinds without the generic catch wrapping it
     * as [ImageCompressionError.Unknown]. [nilDecoderHint] seeds the decoder-name fragment of
     * the nil-image error message — e.g. `UIImage(contentsOfFile)` / `CGImageSourceCreateWithData`
     * — so a `DecodingFailed` surface tells the caller which decode path failed.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <T> runClassifyingIosImageErrors(
        nilDecoderHint: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: ImageCompressionError) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: NullPointerException) {
        throw ImageCompressionError.DecodingFailed(
            "Platform decoder failed (nil image): ${e.message ?: nilDecoderHint}",
            e,
        )
    } catch (e: Throwable) {
        throw ImageCompressionError.Unknown(e.message ?: e::class.simpleName.orEmpty(), e)
    }

    /**
     * Sampled-decode path for a filesystem source. Creates a [CGImageSourceRef] pointed at the
     * file via [CGImageSourceCreateWithURL], asks ImageIO for a thumbnail no larger than
     * [maxDimension] on its long edge, and hands the resulting [platform.CoreGraphics.CGImageRef]
     * to the shared [writeImage] encoder. ImageIO applies EXIF rotation inside its thumbnail
     * pipeline when `kCGImageSourceCreateThumbnailWithTransform = true` so the caller never
     * sees an un-rotated bitmap — no second transform pass required.
     *
     * No `fileExistsAtPath` pre-check: `CGImageSourceCreateWithURL` returns null for a missing
     * file and we map that to `DecodingFailed`. An explicit existence probe would add a file-stat
     * syscall and open a TOCTOU window between the check and the CGImageSource open.
     */
    private suspend fun doThumbnailFromPath(
        inputPath: String,
        outputPath: String,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult = executeThumbnailWithSource(
        spec = ThumbnailSpec(outputPath, maxDimension, format, quality),
        inputSize = nsFileSize(inputPath),
        detectedFormat = detectInputImageFormat(readHeader(inputPath), fileExtension(inputPath)),
        diagnostic = "path=$inputPath",
        openSource = { openCGImageSourceFromPath(inputPath) },
    )

    /**
     * NSData variant of [doThumbnailFromPath]. Uses `CGImageSourceCreateWithData` which is
     * zero-copy for mmap-backed NSData (typical of PhotoKit / `Data(contentsOf:)` inputs) —
     * the sampled decode itself still avoids loading the full-resolution bitmap into RAM.
     */
    private suspend fun doThumbnailFromData(
        data: NSData,
        outputPath: String,
        maxDimension: Int,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult = executeThumbnailWithSource(
        spec = ThumbnailSpec(outputPath, maxDimension, format, quality),
        inputSize = data.length.toLong(),
        detectedFormat = detectInputImageFormat(readHeaderFromData(data), extension = ""),
        diagnostic = "len=${data.length}",
        openSource = { openCGImageSourceFromData(data) },
    )

    /**
     * Shared thumbnail skeleton for the file-path and NSData variants. Handles the gate check,
     * cancellation yield points, decode → encode pipeline, and elapsed-time accounting; the
     * caller-supplied [openSource] factory is the only per-variant branch and owns its own
     * CGImageSource lifecycle (including any CF bridging retain/release pairs).
     */
    private suspend fun executeThumbnailWithSource(
        spec: ThumbnailSpec,
        inputSize: Long,
        detectedFormat: InputImageFormat,
        diagnostic: String,
        openSource: () -> CGImageSourceRef,
    ): CompressionResult {
        throwIfIosIncompatible(spec.format, detectedFormat, iosMajorVersion())
        val startTime = CFAbsoluteTimeGetCurrent()
        val source = openSource()
        currentCoroutineContext().ensureActive()
        try {
            val thumbnailImage = decodeThumbnailUIImage(source, spec.maxDimension, diagnostic)
            currentCoroutineContext().ensureActive()
            val thumbnailConfig = ImageCompressionConfig(format = spec.format, quality = spec.quality)
            writeImage(thumbnailImage, spec.outputPath, thumbnailConfig)
        } finally {
            CFRelease(source)
        }
        val outputSize = nsFileSize(spec.outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        return CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Output + quality settings for a single thumbnail pass — collapses the four-parameter tail
     * that both `doThumbnailFromPath` and `doThumbnailFromData` share into a single argument so
     * `executeThumbnailWithSource` stays under Detekt's default `LongParameterList` ceiling.
     */
    private data class ThumbnailSpec(
        val outputPath: String,
        val maxDimension: Int,
        val format: ImageFormat,
        val quality: Int,
    )

    @Suppress("USELESS_ELVIS")
    private fun openCGImageSourceFromPath(inputPath: String): CGImageSourceRef {
        val urlCF = bridgeUrl(inputPath)
        return try {
            CGImageSourceCreateWithURL(urlCF, options = null)
                ?: throw ImageCompressionError.DecodingFailed("Cannot open CGImageSource for: $inputPath")
        } finally {
            CFRelease(urlCF)
        }
    }

    @Suppress("USELESS_ELVIS", "UNCHECKED_CAST")
    private fun openCGImageSourceFromData(data: NSData): CGImageSourceRef {
        // NSData → CFDataRef via CFBridgingRetain. CGImageSource retains its own reference for
        // the lifetime of the source so we release ours immediately after construction; the
        // source stays valid for the thumbnail decode its caller performs.
        val dataCF = CFBridgingRetain(data)
            ?: throw ImageCompressionError.DecodingFailed("Failed to bridge NSData to CFDataRef")
        return try {
            CGImageSourceCreateWithData(
                dataCF as platform.CoreFoundation.CFDataRef?,
                options = null,
            ) ?: throw ImageCompressionError.DecodingFailed(
                "Cannot open CGImageSource from NSData (length=${data.length})",
            )
        } finally {
            CFRelease(dataCF)
        }
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
 * Builds the 3-entry thumbnail options CFDictionary and invokes [block] with it. Mirrors the
 * shape of [withQualityOptions] above — CFDictionaryCreate + CFRelease in a finally — but with
 * three entries: the integer `kCGImageSourceThumbnailMaxPixelSize`, and two boolean flags
 * (`kCGImageSourceCreateThumbnailFromImageAlways` = true forces sampled decode even when the
 * source has no embedded thumbnail at the requested size; `kCGImageSourceCreateThumbnailWithTransform`
 * = true bakes EXIF orientation into the pixel data so the caller never sees an un-rotated
 * bitmap). All CF references are released before [block] returns.
 */
@Suppress("UNCHECKED_CAST")
private inline fun <R> withThumbnailOptions(maxDim: Int, block: (CFDictionaryRef?) -> R): R =
    memScoped {
        val maxDimVar = alloc<kotlinx.cinterop.IntVar>().apply { value = maxDim }
        val maxDimNumber: CFNumberRef = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, maxDimVar.ptr)
            ?: return@memScoped block(null)
        try {
            val dict = buildThumbnailOptionsDictionary(this, maxDimNumber)
            try {
                block(dict)
            } finally {
                if (dict != null) CFRelease(dict)
            }
        } finally {
            CFRelease(maxDimNumber)
        }
    }

/**
 * Populates the keys/values arrays for the 3-entry thumbnail options dict and calls
 * [CFDictionaryCreate]. Extracted from [withThumbnailOptions] so the retain/release ladder for
 * `maxDimNumber` stays one `try/finally` deep and the body fits the Detekt length limit.
 */
@Suppress("UNCHECKED_CAST")
private fun buildThumbnailOptionsDictionary(
    scope: kotlinx.cinterop.MemScope,
    maxDimNumber: CFNumberRef,
): CFDictionaryRef? = with(scope) {
    val keys = allocArray<COpaquePointerVar>(THUMBNAIL_OPTION_COUNT)
    val values = allocArray<COpaquePointerVar>(THUMBNAIL_OPTION_COUNT)
    keys[KEY_INDEX_MAX_PIXEL_SIZE] = kCGImageSourceThumbnailMaxPixelSize as COpaquePointer?
    values[KEY_INDEX_MAX_PIXEL_SIZE] = maxDimNumber as COpaquePointer
    keys[KEY_INDEX_CREATE_ALWAYS] = kCGImageSourceCreateThumbnailFromImageAlways as COpaquePointer?
    values[KEY_INDEX_CREATE_ALWAYS] = kCFBooleanTrue as COpaquePointer?
    keys[KEY_INDEX_WITH_TRANSFORM] = kCGImageSourceCreateThumbnailWithTransform as COpaquePointer?
    values[KEY_INDEX_WITH_TRANSFORM] = kCFBooleanTrue as COpaquePointer?
    CFDictionaryCreate(
        kCFAllocatorDefault,
        keys,
        values,
        THUMBNAIL_OPTION_COUNT.toLong(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
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

/**
 * Read only the first [IMAGE_SNIFF_BYTES] of [path] via [NSFileHandle] — a full-file
 * `NSData.dataWithContentsOfFile(path)` would materialise the entire payload into RAM just
 * to sniff the magic bytes, defeating `thumbnail()`'s memory-efficiency claim for multi-MB
 * inputs. `readDataOfLength` returns at most the requested length, so the peak heap cost
 * is bounded by the 32-byte prefix regardless of source size.
 */
private fun readHeader(path: String): ByteArray {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0)
    return try {
        val data = handle.readDataOfLength(IMAGE_SNIFF_BYTES.toULong())
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
