/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.ExperimentalKompressorApi
import co.crackn.kompressor.awaitExportSession
import co.crackn.kompressor.cinterop.KMP_safeCreateWriterInput
import co.crackn.kompressor.awaitWriterFinish
import co.crackn.kompressor.awaitWriterReady
import co.crackn.kompressor.checkWriterCompleted
import co.crackn.kompressor.deletingOutputOnFailure
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.image.iosMajorVersion
import co.crackn.kompressor.image.iosOutputGate
import co.crackn.kompressor.image.writeUIImageToFile
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.MediaType
import co.crackn.kompressor.io.PHAssetIcloudOnlyException
import co.crackn.kompressor.io.PHAssetResolutionException
import co.crackn.kompressor.io.toIosInputPath
import co.crackn.kompressor.io.toIosOutputHandle
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.logging.redactPath
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetExportPresetHEVCHighestQuality
import platform.AVFoundation.AVAssetExportPresetMediumQuality
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecH264
import platform.AVFoundation.AVVideoCodecHEVC
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_2020
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_2020
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGImageRef
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.VideoToolbox.kVTProfileLevel_HEVC_Main10_AutoLevel
import platform.CoreGraphics.CGSizeMake

// Baseline input/output MIME coverage for AVFoundation on iOS 15+. VideoToolbox
// can decode additional formats on newer chipsets (ProRes, VP9), but H.264 +
// HEVC (including 10-bit on A10 Fusion and later) are the guaranteed matrix.
private val IOS_SUPPORTED_INPUT_MIMES: Set<String> = setOf("video/avc", "video/hevc")

// H.264 + HEVC are both wired in buildVideoSettings. HEVC is required for HDR10 output.
private val IOS_SUPPORTED_OUTPUT_MIMES: Set<String> = setOf("video/avc", "video/hevc")

/** iOS video compressor backed by [AVAssetReader] and [AVAssetWriter]. */
@OptIn(ExperimentalForeignApi::class)
// TooManyFunctions suppressed: CRA-109 added the `thumbnail()` override plus
// `thumbnailFilePath` / `extractThumbnailCGImage` / `encodeThumbnailCGImage` / `remapImageOutputGate`
// helpers, which are tightly scoped to the frame-extraction pipeline that has no plausible
// alternative host. Splitting into a separate file would fragment the shared typed-error /
// instrumentation glue with the main compress path.
@Suppress("TooManyFunctions")
internal class IosVideoCompressor(
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
) : VideoCompressor {

    override val supportedInputFormats: Set<String> = IOS_SUPPORTED_INPUT_MIMES
    override val supportedOutputFormats: Set<String> = IOS_SUPPORTED_OUTPUT_MIMES

    // LongMethod suppressed: same rationale as IosAudioCompressor.compress — nested try/finally
    // is mandated by lifecycle cleanup correctness and extracting the inner block fragments
    // the contract across two call sites.
    @Suppress("LongMethod")
    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: VideoCompressionConfig,
        onProgress: suspend (CompressionProgress) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        val inHandle = try {
            input.toIosInputPath(
                mediaType = MediaType.VIDEO,
                logger = logger,
                onProgress = { fraction ->
                    onProgress(
                        CompressionProgress(
                            CompressionProgress.Phase.MATERIALIZING_INPUT,
                            fraction.coerceIn(0f, 1f),
                        ),
                    )
                },
            )
        } catch (e: PHAssetIcloudOnlyException) {
            throw VideoCompressionError.SourceNotFound(e.message ?: "PHAsset iCloud-only", cause = e)
        } catch (e: PHAssetResolutionException) {
            throw VideoCompressionError.IoFailed(e.message ?: "PHAsset resolution failed", cause = e)
        }
        // Nested try/finally so `inHandle.cleanup()` fires even when `toIosOutputHandle()` throws.
        // Mirrors the Android sibling [PR #142 review, finding #1].
        try {
            val outHandle = output.toIosOutputHandle()
            try {
                val result = compressFilePath(inHandle.path, outHandle.tempPath, config) { fraction ->
                    // FINALIZING_OUTPUT(1f) is the canonical terminal — don't double-signal 100%
                    // by forwarding the inner pipeline's own 1f tick as a COMPRESSING emission.
                    if (fraction < 1f) {
                        onProgress(
                            CompressionProgress(
                                CompressionProgress.Phase.COMPRESSING,
                                fraction.coerceIn(0f, 1f),
                            ),
                        )
                    }
                }
                outHandle.commit { fraction ->
                    if (fraction < 1f) {
                        onProgress(
                            CompressionProgress(
                                CompressionProgress.Phase.FINALIZING_OUTPUT,
                                fraction.coerceIn(0f, 1f),
                            ),
                        )
                    }
                }
                onProgress(CompressionProgress(CompressionProgress.Phase.FINALIZING_OUTPUT, 1f))
                result
            } finally {
                outHandle.cleanup()
            }
        } finally {
            inHandle.cleanup()
        }
    }

    // LongMethod suppressed: the extra length is from the CRA-47 `instrumentCompress`
    // wrapping (tag + three message builders + pipeline-selection DEBUG log) plus the
    // HDR10 pre-flight. Extracting the inner block to a helper would fragment the path-based
    // pipeline contract across two call sites; keeping it inline reads straight through.
    @Suppress("LongMethod")
    private suspend fun compressFilePath(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.VIDEO,
        startMessage = {
            "compress() start in=${redactPath(inputPath)} out=${redactPath(outputPath)} " +
                "codec=${config.codec} dynamicRange=${config.dynamicRange} " +
                "videoBitrate=${config.videoBitrate} maxRes=${config.maxResolution}"
        },
        successMessage = { r ->
            "compress() ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
        },
        failureMessage = { "compress() failed in=${redactPath(inputPath)}" },
    ) {
        val startTime = CFAbsoluteTimeGetCurrent()
        onProgress(0f)
        val inputSize = sizeOrTypedError(inputPath)
        // Pre-flight: reject inputs with no video track (audio-only MP4s) with a typed error so
        // callers see the same `UnsupportedSourceFormat` subtype as the Android side, rather
        // than a generic `IllegalArgumentException` from deep in the pipeline.
        validateHasVideoTrack(inputPath)
        // Pre-flight HDR10: mirrors Android's `requireHdr10Hevc`. AVFoundation on A9/iOS 15
        // accepts the settings dictionary but crashes mid-pipeline with an uncatchable
        // NSException; asking `canApplyOutputSettings` first turns that into a typed
        // `UnsupportedSourceFormat` before the writer is ever started.
        if (config.dynamicRange == DynamicRange.HDR10) {
            requireHdr10HevcCapability(config.codec)
        }
        runPipelineWithTypedErrors(outputPath) {
            val preset = exportSessionPresetOrNull(config)
            logger.debug(LogTags.VIDEO) {
                "Pipeline: ${if (preset != null) "AVAssetExportSession($preset)" else "AVAssetWriter"}"
            }
            if (preset != null) {
                IosVideoExportPipeline(inputPath, outputPath, preset).execute(onProgress)
            } else {
                IosVideoTranscodePipeline(inputPath, outputPath, config).execute(onProgress)
            }
        }
        onProgress(1f)
        // Route the output-size read through `sizeOrTypedError` too: a race that loses the
        // output file between pipeline success and this read would otherwise leak the untyped
        // `IllegalStateException("Cannot read file size")` from `nsFileSize` into
        // `Result.failure`, which `suspendRunCatching` passes through unchanged.
        val outputSize = sizeOrTypedError(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        CompressionResult(inputSize, outputSize, durationMs)
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun sizeOrTypedError(path: String): Long =
        try {
            nsFileSize(path)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (typed: VideoCompressionError) {
            throw typed
        } catch (t: Throwable) {
            throw mapToVideoError(t)
        }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend inline fun runPipelineWithTypedErrors(outputPath: String, block: () -> Unit) {
        try {
            deletingOutputOnFailure(outputPath) { block() }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (typed: VideoCompressionError) {
            throw typed
        } catch (t: Throwable) {
            throw mapToVideoError(t)
        }
    }


    /**
     * Reject audio-only inputs upfront with a typed [VideoCompressionError.UnsupportedSourceFormat].
     * Uses the same `tracksWithMediaType` check the pipelines use internally; failing here means
     * callers see a clean typed error instead of racing a generic `IllegalArgumentException`
     * from deep in `execute()`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun validateHasVideoTrack(inputPath: String) {
        val hasVideo = try {
            AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
                .tracksWithMediaType(AVMediaTypeVideo).isNotEmpty()
        } catch (_: Throwable) {
            // Treat probe failures as "unknown" — the real pipeline will surface its own error.
            return
        }
        if (!hasVideo) {
            throw VideoCompressionError.UnsupportedSourceFormat(
                "Input has no video track (only audio): $inputPath",
            )
        }
    }

    /**
     * Throw a typed [VideoCompressionError.UnsupportedSourceFormat] when the runtime can't honour
     * HDR10 output. `AVAssetWriter.canApplyOutputSettings` is AVFoundation's documented probe:
     * it returns `false` when the device (e.g. A9 on iOS 15) lacks the hardware Main10 encoder
     * that the BT.2020+PQ settings dictionary requires, so the failure surfaces here instead of
     * crashing `AVAssetWriterInput.init` with an Obj-C exception we cannot catch.
     *
     * The probe writes to a throwaway path under `NSTemporaryDirectory()` and never calls
     * `startWriting`, so no bytes are produced on disk.
     */
    private fun requireHdr10HevcCapability(codec: VideoCodec) {
        val tmpUrl = NSURL.fileURLWithPath(
            platform.Foundation.NSTemporaryDirectory() + "kompressor-hdr10-probe-" +
                platform.Foundation.NSUUID().UUIDString + ".mp4",
        )
        val writer = AVAssetWriter.assetWriterWithURL(tmpUrl, fileType = AVFileTypeMPEG4, error = null)
        val supported = writer?.canApplyOutputSettings(
            HDR10_PROBE_SETTINGS,
            forMediaType = AVMediaTypeVideo,
        ) ?: false
        if (!supported) {
            throw VideoCompressionError.UnsupportedSourceFormat(
                "HEVC Main10 HDR10 encoder unavailable on this device " +
                    "(requested DynamicRange.HDR10 with codec=$codec)",
            )
        }
    }

    @ExperimentalKompressorApi
    // LongMethod suppressed: body is dominated by the `require(...)` contract checks, the
    // `suspendRunCatching` wrapper, and the nested try/finally pairs that own the input-handle
    // (PHAsset remap) and output-handle (temp-path commit/cleanup) lifecycles. Splitting would
    // fragment error remapping from the lifecycle it guards.
    @Suppress("LongMethod")
    override suspend fun thumbnail(
        input: MediaSource,
        output: MediaDestination,
        atMillis: Long,
        maxDimension: Int?,
        format: ImageFormat,
        quality: Int,
    ): Result<CompressionResult> {
        require(atMillis >= 0) { "atMillis must be >= 0 (was $atMillis)" }
        require(quality in 0..MAX_QUALITY) { "quality must be in 0..100 (was $quality)" }
        require(maxDimension == null || maxDimension > 0) {
            "maxDimension must be > 0 or null (was $maxDimension)"
        }
        return suspendRunCatching {
            // Gate the output format up front (AVIF on iOS 15, WEBP always). Remap the image
            // hierarchy's typed error to the video hierarchy so consumers `when`-branch on
            // VideoCompressionError.
            iosOutputGate(format, iosMajorVersion())?.let { throw remapImageOutputGate(it) }
            val inHandle = try {
                input.toIosInputPath(mediaType = MediaType.VIDEO, logger = logger)
            } catch (e: PHAssetIcloudOnlyException) {
                throw VideoCompressionError.SourceNotFound(e.message ?: "PHAsset iCloud-only", cause = e)
            } catch (e: PHAssetResolutionException) {
                throw VideoCompressionError.IoFailed(e.message ?: "PHAsset resolution failed", cause = e)
            }
            try {
                val outHandle = output.toIosOutputHandle()
                try {
                    val result = thumbnailFilePath(
                        inHandle.path, outHandle.tempPath,
                        atMillis, maxDimension, format, quality,
                    )
                    outHandle.commit()
                    result
                } finally {
                    outHandle.cleanup()
                }
            } finally {
                inHandle.cleanup()
            }
        }
    }

    // LongMethod suppressed: the `instrumentCompress` block wraps observability around the
    // duration probe, typed-error remapping, CGImage extraction, and CFRelease cleanup. These
    // steps share the timing/size bookkeeping and cannot be split without leaking the
    // instrumentation boundary.
    @Suppress("LongParameterList", "LongMethod")
    private suspend fun thumbnailFilePath(
        inputPath: String,
        outputPath: String,
        atMillis: Long,
        maxDimension: Int?,
        format: ImageFormat,
        quality: Int,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.VIDEO,
        startMessage = {
            "thumbnail() start in=${redactPath(inputPath)} out=${redactPath(outputPath)} " +
                "atMillis=$atMillis max=$maxDimension fmt=$format quality=$quality"
        },
        successMessage = { r ->
            "thumbnail() ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B"
        },
        failureMessage = { "thumbnail() failed in=${redactPath(inputPath)}" },
    ) {
        val startTime = CFAbsoluteTimeGetCurrent()
        val inputSize = sizeOrTypedError(inputPath)
        // Pre-flight: reject audio-only inputs upfront so consumers see the same
        // `UnsupportedSourceFormat` subtype `compress()` produces — without this guard the
        // failure surfaces deeper in `AVAssetImageGenerator.copyCGImageAtTime` as a
        // `DecodingFailed`, which breaks the "single `when` branch covers both APIs" contract.
        validateHasVideoTrack(inputPath)
        runPipelineWithTypedErrors(outputPath) {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
            val durationSec = CMTimeGetSeconds(asset.duration)
            // Only enforce the out-of-range guard when the asset actually reported a positive
            // duration. `NaN` / negative comes back from corrupted containers and some codec
            // parser quirks — treating that as `duration=0` would surface a misleading
            // `TimestampOutOfRange` for any non-zero `atMillis`, so defer classification to the
            // decode path which fails with `DecodingFailed`.
            if (durationSec.isFinite() && durationSec > 0.0) {
                val durationMs = (durationSec * MILLIS_PER_SEC).toLong()
                if (atMillis > durationMs) {
                    throw VideoCompressionError.TimestampOutOfRange(
                        "atMillis=$atMillis > duration=$durationMs",
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            val cgImage = extractThumbnailCGImage(asset, atMillis, maxDimension, inputPath)
            try {
                encodeThumbnailCGImage(cgImage, outputPath, format, quality)
            } finally {
                CFRelease(cgImage)
            }
        }
        val outputSize = sizeOrTypedError(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Extract a keyframe near [atMillis] via [AVAssetImageGenerator]. `appliesPreferredTrackTransform
     * = true` honours the track's display orientation so portrait recordings stay portrait;
     * `maximumSize` caps the longer edge via AVFoundation's own downscale (equivalent to Android's
     * `getScaledFrameAtTime`). `requestedTimeToleranceBefore = 0` combined with a 100 ms
     * `requestedTimeToleranceAfter` matches the keyframe-approximation story in the common KDoc.
     */
    @OptIn(BetaInteropApi::class)
    private fun extractThumbnailCGImage(
        asset: AVURLAsset,
        atMillis: Long,
        maxDimension: Int?,
        inputPath: String,
    ): CGImageRef = memScoped {
        val generator = AVAssetImageGenerator(asset = asset).apply {
            appliesPreferredTrackTransform = true
            requestedTimeToleranceBefore = CMTimeMake(value = 0, timescale = TIMESCALE_MILLIS)
            requestedTimeToleranceAfter = CMTimeMake(
                value = TOLERANCE_AFTER_MILLIS,
                timescale = TIMESCALE_MILLIS,
            )
            if (maxDimension != null) {
                maximumSize = CGSizeMake(maxDimension.toDouble(), maxDimension.toDouble())
            }
        }
        // Use CMTimeMake with the millisecond timescale directly — avoids the double-to-CMTime
        // rounding round-trip that CMTimeMakeWithSeconds introduces for no gain here.
        val requestedTime = CMTimeMake(value = atMillis, timescale = TIMESCALE_MILLIS)
        // Capture the NSError out-param so `DecodingFailed` carries AVFoundation's domain/code
        // (e.g. AVFoundationErrorDomain / AVErrorNoImageAtTime). Passing `error = null` would
        // silently drop that diagnostic context and mask codec- vs. container- vs. timestamp-
        // specific failures behind a single generic message.
        val errorRef = alloc<ObjCObjectVar<NSError?>>()
        val cgImage = generator.copyCGImageAtTime(
            requestedTime = requestedTime,
            actualTime = null,
            error = errorRef.ptr,
        )
        cgImage ?: run {
            val nsError = errorRef.value
            val detail = nsError?.let { " (${it.domain}:${it.code} ${it.localizedDescription})" }.orEmpty()
            throw VideoCompressionError.DecodingFailed(
                "Failed to extract frame at ${atMillis}ms from $inputPath$detail",
            )
        }
    }

    private fun encodeThumbnailCGImage(
        cgImage: CGImageRef,
        outputPath: String,
        format: ImageFormat,
        quality: Int,
    ) {
        // `UIImage.imageWithCGImage(_:)` preserves the already-oriented pixel buffer from the
        // generator (appliesPreferredTrackTransform = true baked orientation into the CGImage).
        // The single-arg overload implicitly uses scale=1.0 and orientation=.up, which avoids
        // the Retina-doubling trap when the resulting UIImage flows into `UIImageJPEGRepresentation`
        // on the JPEG branch.
        val uiImage = UIImage.imageWithCGImage(cgImage)
        try {
            writeUIImageToFile(uiImage, outputPath, format, quality)
        } catch (e: ImageCompressionError.EncodingFailed) {
            throw VideoCompressionError.EncodingFailed(e.details, cause = e)
        } catch (e: ImageCompressionError.UnsupportedOutputFormat) {
            // The iosOutputGate upstream already converted known gate failures, but
            // CGImageDestinationCreateWithURL can still refuse at runtime on eg. AVIF over
            // older SDKs than our matrix advertises — remap defensively.
            throw remapImageOutputGate(e)
        }
    }

    private fun remapImageOutputGate(
        err: ImageCompressionError.UnsupportedOutputFormat,
    ): VideoCompressionError.EncodingFailed = VideoCompressionError.EncodingFailed(
        "Thumbnail output format '${err.format}' unsupported on iOS " +
            "(requires iOS ${err.minApi}+)",
        cause = err,
    )

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
        const val MAX_QUALITY = 100
        // AVFoundation uses CMTime for timestamps; a 1 ms timescale lets us pass `atMillis`
        // as the `value` component directly and keeps the 100 ms tolerance arithmetic obvious.
        const val TIMESCALE_MILLIS = 1000
        const val TOLERANCE_AFTER_MILLIS = 100L

        // Minimum probe dimension for `canApplyOutputSettings` HDR10 pre-flight.
        // 16×16 returns false on some devices; 64×64 matches the fixture and works reliably.
        private const val HDR10_PROBE_DIM = 64
        private val HDR10_PROBE_SETTINGS: Map<Any?, Any?> = mapOf(
            AVVideoCodecKey to AVVideoCodecHEVC,
            AVVideoWidthKey to HDR10_PROBE_DIM,
            AVVideoHeightKey to HDR10_PROBE_DIM,
            AVVideoColorPropertiesKey to mapOf(
                AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_2020,
                AVVideoTransferFunctionKey to AVVideoTransferFunction_SMPTE_ST_2084_PQ,
                AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_2020,
            ),
            AVVideoCompressionPropertiesKey to mapOf(
                AVVideoProfileLevelKey to kVTProfileLevel_HEVC_Main10_AutoLevel,
            ),
        )
    }
}

// ── Fast-path eligibility ────────────────────────────────────────────

/**
 * The single HDR10 fast-path config we accept. Any deviation (custom bitrate, resolution,
 * framerate, keyframe interval) forces the custom transcode pipeline because
 * `AVAssetExportSession` presets do not expose those knobs.
 */
internal val HDR10_FAST_PATH_CONFIG: VideoCompressionConfig = VideoCompressionConfig(
    codec = VideoCodec.HEVC,
    dynamicRange = DynamicRange.HDR10,
)

/**
 * Pick an [AVAssetExportSession] preset that matches [config], or return `null` when the
 * config is too specific to honour via Apple's fixed presets.
 *
 * The fast path covers two cases:
 * 1. **SDR default** — `VideoCompressionConfig()` maps to [AVAssetExportPresetMediumQuality].
 *    This has been the historical baseline.
 * 2. **HDR10 HEVC at otherwise-default settings** — [HDR10_FAST_PATH_CONFIG] maps to
 *    [AVAssetExportPresetHEVCHighestQuality], which preserves HDR10 color metadata (BT.2020
 *    primaries + PQ transfer + BT.2020 matrix) when the source is HDR10. We gate HDR10 fast-
 *    path eligibility on the platform capability probe (`requireHdr10HevcCapability`) which
 *    has already run upstream — if the device lacks a Main10 encoder `compress()` throws
 *    [VideoCompressionError.UnsupportedSourceFormat] before this function is reached, so
 *    selecting the HEVC preset here is safe.
 *
 * Any other tuning (custom bitrate, framerate cap, resolution cap, keyframe interval, H.264
 * with non-default trim) falls back to the custom transcode pipeline because
 * `AVAssetExportSession` can't honour arbitrary settings — presets are opaque quality levels,
 * not numeric knobs.
 */
internal fun exportSessionPresetOrNull(config: VideoCompressionConfig): String? = when (config) {
    VideoCompressionConfig() -> AVAssetExportPresetMediumQuality
    HDR10_FAST_PATH_CONFIG -> AVAssetExportPresetHEVCHighestQuality
    else -> null
}

// ── Custom pipeline: exact bitrate/resolution/framerate control ─────

/**
 * Transcodes video using [AVAssetReader]/[AVAssetWriter] with explicit
 * H.264 encoding settings for full control over bitrate, resolution, and framerate.
 */
@Suppress("TooManyFunctions")
@OptIn(ExperimentalForeignApi::class)
private class IosVideoTranscodePipeline(
    inputPath: String,
    outputPath: String,
    private val config: VideoCompressionConfig,
) {
    private val inputUrl = NSURL.fileURLWithPath(inputPath)
    private val outputUrl = NSURL.fileURLWithPath(outputPath)
    private val asset = AVURLAsset(uRL = inputUrl, options = null)

    @Suppress("UNCHECKED_CAST")
    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        // Throw the typed error directly rather than an untyped IllegalArgumentException so
        // this site is compliant with the "no raw throws from public-API code paths" taxonomy
        // audit (CRA-21) instead of relying on the outer `runPipelineWithTypedErrors` to remap
        // — a future refactor that moves the call site out of that wrapper would otherwise
        // silently regress the typed-error contract.
        val videoTrack = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: throw VideoCompressionError.UnsupportedSourceFormat(
                "No video track found in input file",
            )
        val audioTrack = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack

        val totalDurationSec = CMTimeGetSeconds(asset.duration)
        val (targetW, targetH) = calculateTarget(videoTrack)

        onProgress(PROGRESS_SETUP)
        currentCoroutineContext().ensureActive()

        val (reader, videoOutput, audioOutput) = createReader(videoTrack, audioTrack)
        val (writer, videoInput, audioInput) = createWriter(targetW, targetH, audioTrack, videoTrack)

        try {
            startReaderWriter(reader, writer)
            copyAllSamples(
                writer, videoOutput, videoInput,
                audioOutput, audioInput, totalDurationSec, onProgress,
            )
            finishWriting(reader, writer, videoInput, audioInput)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            reader.cancelReading()
            writer.cancelWriting()
            throw e
        }
    }

    private fun calculateTarget(videoTrack: AVAssetTrack): Pair<Int, Int> {
        val (sourceW, sourceH) = videoTrack.naturalSize.useContents {
            width.toInt() to height.toInt()
        }
        return ResolutionCalculator.calculate(sourceW, sourceH, config.maxResolution)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createReader(
        videoTrack: AVAssetTrack,
        audioTrack: AVAssetTrack?,
    ): Triple<AVAssetReader, AVAssetReaderTrackOutput, AVAssetReaderTrackOutput?> {
        val reader = AVAssetReader(asset = asset, error = null)
        // Request decoded pixel buffers (not compressed passthrough) so the
        // writer's encoder can re-encode at the target bitrate/resolution.
        // The raw string "PixelFormatType" is the underlying value of
        // kCVPixelBufferPixelFormatTypeKey (see CVPixelBuffer.h). We use the
        // literal because the CFStringRef constant does not bridge to NSDictionary
        // keys in Kotlin/Native.
        // HDR10 requires 10-bit P010 pixel buffers — VideoToolbox rejects 8-bit
        // BGRA with BT.2020/PQ color properties on real devices.
        val pixelFormat = if (config.dynamicRange == DynamicRange.HDR10) {
            kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
        } else {
            kCVPixelFormatType_32BGRA
        }
        val videoOutputSettings: Map<Any?, *> = mapOf(
            PIXEL_FORMAT_KEY to pixelFormat,
        )
        val videoOutput = AVAssetReaderTrackOutput(
            track = videoTrack,
            outputSettings = videoOutputSettings,
        )
        reader.addOutput(videoOutput)

        // Audio: passthrough (remux without re-encoding)
        val audioOutput = audioTrack?.let {
            val output = AVAssetReaderTrackOutput(track = it, outputSettings = null)
            reader.addOutput(output)
            output
        }
        return Triple(reader, videoOutput, audioOutput)
    }

    private fun createWriter(
        targetW: Int,
        targetH: Int,
        audioTrack: AVAssetTrack?,
        videoTrack: AVAssetTrack,
    ): Triple<AVAssetWriter, AVAssetWriterInput, AVAssetWriterInput?> {
        val writer = AVAssetWriter.assetWriterWithURL(
            outputUrl, fileType = AVFileTypeMPEG4, error = null,
        ) ?: error("Failed to create AVAssetWriter for: $outputUrl")

        val videoInput = KMP_safeCreateWriterInput(
            mediaType = requireNotNull(AVMediaTypeVideo) { "AVMediaTypeVideo unavailable" },
            outputSettings = buildVideoSettings(targetW, targetH),
        ) ?: throw VideoCompressionError.UnsupportedSourceFormat(
            "AVAssetWriterInput.init threw ObjC NSException — hardware encoder " +
                "rejected HEVC Main10 output settings (see Kompressor NSLog for details)",
        )
        videoInput.expectsMediaDataInRealTime = false
        // Preserve source orientation. `preferredTransform` is an affine matrix that
        // maps the decoded buffer coordinates to display coordinates (e.g. a portrait
        // recording captured as a 1920x1080 landscape buffer has a 90° transform so
        // players render it upright). AVAssetWriterInput.transform carries this onto
        // the output container's `tkhd` matrix, so the encoded frames stay in the raw
        // buffer orientation (targetW x targetH = naturalSize scaled) while the
        // displayed orientation matches the source. Must be set before addInput.
        videoInput.setTransform(videoTrack.preferredTransform)
        writer.addInput(videoInput)

        val audioInput = audioTrack?.let {
            val input = AVAssetWriterInput.assetWriterInputWithMediaType(
                mediaType = AVMediaTypeAudio,
                outputSettings = null, // Passthrough (remux audio)
            )
            input.expectsMediaDataInRealTime = false
            writer.addInput(input)
            input
        }
        return Triple(writer, videoInput, audioInput)
    }

    private fun buildVideoSettings(width: Int, height: Int): Map<Any?, *> {
        val compressionProps = baseCompressionProps()
        val baseSettings = mutableMapOf<Any?, Any?>(
            AVVideoCodecKey to codecKeyFor(config.codec),
            AVVideoWidthKey to width,
            AVVideoHeightKey to height,
        )
        if (config.dynamicRange == DynamicRange.HDR10) {
            compressionProps[AVVideoProfileLevelKey] = kVTProfileLevel_HEVC_Main10_AutoLevel
            baseSettings[AVVideoColorPropertiesKey] = HDR10_COLOR_PROPERTIES
        }
        baseSettings[AVVideoCompressionPropertiesKey] = compressionProps
        return baseSettings
    }

    // AVVideoCodec{H264,HEVC} are typed as `String?` in K/N's AVFoundation bridge despite being
    // non-null CFString constants in the SDK. `requireNotNull` surfaces a typed error if the
    // bridge ever drops them (e.g. SDK strip) rather than a raw NPE.
    private fun codecKeyFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> requireNotNull(AVVideoCodecH264) { "AVVideoCodecH264 unavailable in current K/N bridge" }
        VideoCodec.HEVC -> requireNotNull(AVVideoCodecHEVC) { "AVVideoCodecHEVC unavailable in current K/N bridge" }
    }

    private fun baseCompressionProps(): MutableMap<Any?, Any?> = mutableMapOf(
        AVVideoAverageBitRateKey to config.videoBitrate,
        AVVideoMaxKeyFrameIntervalKey to config.keyFrameInterval * config.maxFrameRate,
        AVVideoExpectedSourceFrameRateKey to config.maxFrameRate,
    )

    private fun startReaderWriter(reader: AVAssetReader, writer: AVAssetWriter) {
        if (!reader.startReading()) {
            val err = reader.error
            if (err != null) throw co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed to start")
            error("AVAssetReader failed to start: unknown")
        }
        if (!writer.startWriting()) {
            val err = writer.error
            if (err != null) throw co.crackn.kompressor.AVNSErrorException(err, "AVAssetWriter failed to start")
            error("AVAssetWriter failed to start: unknown")
        }
        writer.startSessionAtSourceTime(CMTimeMake(value = 0, timescale = 1))
    }

    @Suppress("LongParameterList")
    private suspend fun copyAllSamples(
        writer: AVAssetWriter,
        videoOutput: AVAssetReaderTrackOutput,
        videoInput: AVAssetWriterInput,
        audioOutput: AVAssetReaderTrackOutput?,
        audioInput: AVAssetWriterInput?,
        totalDurationSec: Double,
        onProgress: suspend (Float) -> Unit,
    ) {
        var lastReported = PROGRESS_SETUP
        var videoDone = false
        var audioDone = audioOutput == null

        while (!videoDone || !audioDone) {
            currentCoroutineContext().ensureActive()
            if (!videoDone) {
                videoDone = !copyVideoSample(
                    videoOutput, writer, videoInput, totalDurationSec, lastReported, onProgress,
                ) { lastReported = it }
            }
            if (!audioDone && audioOutput != null && audioInput != null) {
                audioDone = !copyAudioSample(audioOutput, audioInput)
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun copyVideoSample(
        output: AVAssetReaderTrackOutput,
        writer: AVAssetWriter,
        input: AVAssetWriterInput,
        totalDurationSec: Double,
        lastReported: Float,
        onProgress: suspend (Float) -> Unit,
        onUpdateProgress: (Float) -> Unit,
    ): Boolean {
        val buffer = output.copyNextSampleBuffer() ?: return false
        try {
            awaitWriterReady(writer, input)
            reportVideoProgress(buffer, totalDurationSec, lastReported, onProgress, onUpdateProgress)
            check(input.appendSampleBuffer(buffer)) { "Failed to append video sample" }
            return true
        } finally {
            CFRelease(buffer)
        }
    }

    private suspend fun reportVideoProgress(
        buffer: platform.CoreMedia.CMSampleBufferRef,
        totalDurationSec: Double,
        lastReported: Float,
        onProgress: suspend (Float) -> Unit,
        onUpdate: (Float) -> Unit,
    ) {
        if (totalDurationSec <= 0) return
        val sampleSec = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(buffer))
        val fraction = (sampleSec / totalDurationSec).coerceIn(0.0, 1.0).toFloat()
        val progress = PROGRESS_SETUP + PROGRESS_TRANSCODE_RANGE * fraction
        if (progress - lastReported >= PROGRESS_REPORT_THRESHOLD) {
            onProgress(progress)
            onUpdate(progress)
        }
    }

    private fun copyAudioSample(
        output: AVAssetReaderTrackOutput,
        input: AVAssetWriterInput,
    ): Boolean {
        val notReady = !input.readyForMoreMediaData
        if (notReady) return true // Not ready yet, retry later
        val buffer = output.copyNextSampleBuffer()
        if (buffer != null) {
            try {
                check(input.appendSampleBuffer(buffer)) { "Failed to append audio sample" }
            } finally {
                CFRelease(buffer)
            }
        }
        return buffer != null
    }

    private suspend fun finishWriting(
        reader: AVAssetReader,
        writer: AVAssetWriter,
        videoInput: AVAssetWriterInput,
        audioInput: AVAssetWriterInput?,
    ) {
        videoInput.markAsFinished()
        audioInput?.markAsFinished()
        if (reader.status == AVAssetReaderStatusFailed) {
            val err = reader.error
            if (err != null) throw co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed")
            error("AVAssetReader failed: unknown")
        }
        awaitWriterFinish(writer)
        checkWriterCompleted(writer)
    }

    private companion object {
        // kCVPixelBufferPixelFormatTypeKey underlying CFString value (CVPixelBuffer.h)
        const val PIXEL_FORMAT_KEY = "PixelFormatType"
        const val PROGRESS_SETUP = 0.05f
        const val PROGRESS_TRANSCODE_RANGE = 0.90f
        const val PROGRESS_REPORT_THRESHOLD = 0.01f

        // BT.2020 primaries + SMPTE ST 2084 (PQ) transfer + BT.2020 non-constant-luminance
        // Y′CbCr matrix — the canonical HDR10 colour signature. Hoisted out of buildVideoSettings
        // so the dictionary isn't re-allocated on every invocation.
        private val HDR10_COLOR_PROPERTIES: Map<Any?, Any?> = mapOf(
            AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_2020,
            AVVideoTransferFunctionKey to AVVideoTransferFunction_SMPTE_ST_2084_PQ,
            AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_2020,
        )
    }
}

// ── Export session fast path ────────────────────────────────────────

/** Uses [AVAssetExportSession] for hardware-accelerated video compression with preset quality. */
@OptIn(ExperimentalForeignApi::class)
private class IosVideoExportPipeline(
    inputPath: String,
    private val outputPath: String,
    private val presetName: String,
) {
    private val inputUrl = NSURL.fileURLWithPath(inputPath)
    private val outputUrl = NSURL.fileURLWithPath(outputPath)

    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        val asset = AVURLAsset(uRL = inputUrl, options = null)
        val session = AVAssetExportSession.exportSessionWithAsset(
            asset = asset,
            presetName = presetName,
        ) ?: error("AVAssetExportSession not available for input (preset=$presetName)")
        session.outputURL = outputUrl
        session.outputFileType = AVFileTypeMPEG4

        coroutineScope {
            val progressJob = launch {
                var lastReported = 0f
                while (isActive) {
                    val progress = session.progress
                    if (progress - lastReported >= PROGRESS_REPORT_THRESHOLD) {
                        onProgress(progress)
                        lastReported = progress
                    }
                    delay(PROGRESS_POLL_INTERVAL_MS)
                }
            }
            try {
                awaitExportSession(session)
            } finally {
                progressJob.cancel()
            }
        }
    }

    private companion object {
        const val PROGRESS_POLL_INTERVAL_MS = 100L
        const val PROGRESS_REPORT_THRESHOLD = 0.01f
    }
}
