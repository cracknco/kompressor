package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.awaitExportSession
import co.crackn.kompressor.cinterop.KMP_safeCreateWriterInput
import co.crackn.kompressor.awaitWriterFinish
import co.crackn.kompressor.awaitWriterReady
import co.crackn.kompressor.checkWriterCompleted
import co.crackn.kompressor.deletingOutputOnFailure
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetExportPresetMediumQuality
import platform.AVFoundation.AVAssetExportSession
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
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.Foundation.NSURL
import platform.VideoToolbox.kVTProfileLevel_HEVC_Main10_AutoLevel

// Baseline input/output MIME coverage for AVFoundation on iOS 15+. VideoToolbox
// can decode additional formats on newer chipsets (ProRes, VP9), but H.264 +
// HEVC (including 10-bit on A10 Fusion and later) are the guaranteed matrix.
private val IOS_SUPPORTED_INPUT_MIMES: Set<String> = setOf("video/avc", "video/hevc")

// H.264 + HEVC are both wired in buildVideoSettings. HEVC is required for HDR10 output.
private val IOS_SUPPORTED_OUTPUT_MIMES: Set<String> = setOf("video/avc", "video/hevc")

/** iOS video compressor backed by [AVAssetReader] and [AVAssetWriter]. */
@OptIn(ExperimentalForeignApi::class)
internal class IosVideoCompressor : VideoCompressor {

    override val supportedInputFormats: Set<String> = IOS_SUPPORTED_INPUT_MIMES
    override val supportedOutputFormats: Set<String> = IOS_SUPPORTED_OUTPUT_MIMES

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
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
            if (canUseExportSession(config)) {
                IosVideoExportPipeline(inputPath, outputPath).execute(onProgress)
            } else {
                IosVideoTranscodePipeline(inputPath, outputPath, config).execute(onProgress)
            }
        }
        onProgress(1f)
        val outputSize = nsFileSize(outputPath)
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

    private fun canUseExportSession(config: VideoCompressionConfig): Boolean =
        config == VideoCompressionConfig()

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

    private companion object {
        const val MILLIS_PER_SEC = 1000.0

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
        val videoTrack = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: throw IllegalArgumentException("No video track found in input file")
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
) {
    private val inputUrl = NSURL.fileURLWithPath(inputPath)
    private val outputUrl = NSURL.fileURLWithPath(outputPath)

    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        val asset = AVURLAsset(uRL = inputUrl, options = null)
        val session = AVAssetExportSession.exportSessionWithAsset(
            asset = asset,
            presetName = AVAssetExportPresetMediumQuality,
        ) ?: error("AVAssetExportSession not available for input")
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
