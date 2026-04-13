package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.awaitExportSession
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
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.naturalSize
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL

// Baseline input/output MIME coverage for AVFoundation on iOS 15+. VideoToolbox
// can decode additional formats on newer chipsets (ProRes, VP9), but H.264 +
// HEVC (including 10-bit on A10 Fusion and later) are the guaranteed matrix.
private val IOS_SUPPORTED_INPUT_MIMES: Set<String> = setOf("video/avc", "video/hevc")

// Only H.264 is actually wired in buildVideoSettings today. Expand this when
// VideoCodec gains an HEVC variant AND buildVideoSettings handles it — keeping
// the advertised set in sync with the implementation avoids misleading callers
// that inspect supportedOutputFormats before calling compress().
private val IOS_SUPPORTED_OUTPUT_MIMES: Set<String> = setOf("video/avc")

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

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
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
        val (writer, videoInput, audioInput) = createWriter(targetW, targetH, audioTrack)

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
        // writer's H.264 encoder can re-encode at the target bitrate/resolution.
        // The raw string "PixelFormatType" is the underlying value of
        // kCVPixelBufferPixelFormatTypeKey (see CVPixelBuffer.h). We use the
        // literal because the CFStringRef constant does not bridge to NSDictionary
        // keys in Kotlin/Native.
        val videoOutputSettings: Map<Any?, *> = mapOf(
            PIXEL_FORMAT_KEY to platform.CoreVideo.kCVPixelFormatType_32BGRA,
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
    ): Triple<AVAssetWriter, AVAssetWriterInput, AVAssetWriterInput?> {
        val writer = AVAssetWriter.assetWriterWithURL(
            outputUrl, fileType = AVFileTypeMPEG4, error = null,
        ) ?: error("Failed to create AVAssetWriter for: $outputUrl")

        val videoInput = AVAssetWriterInput.assetWriterInputWithMediaType(
            mediaType = AVMediaTypeVideo,
            outputSettings = buildVideoSettings(targetW, targetH),
        )
        videoInput.expectsMediaDataInRealTime = false
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

    private fun buildVideoSettings(width: Int, height: Int): Map<Any?, *> = mapOf(
        // Dispatched via `when` so the compiler forces this to stay in sync when
        // the VideoCodec enum grows a new variant (e.g. HEVC).
        AVVideoCodecKey to when (config.codec) {
            co.crackn.kompressor.video.VideoCodec.H264 -> AVVideoCodecH264
        },
        AVVideoWidthKey to width,
        AVVideoHeightKey to height,
        AVVideoCompressionPropertiesKey to mapOf(
            AVVideoAverageBitRateKey to config.videoBitrate,
            AVVideoMaxKeyFrameIntervalKey to config.keyFrameInterval * config.maxFrameRate,
            AVVideoExpectedSourceFrameRateKey to config.maxFrameRate,
        ),
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
