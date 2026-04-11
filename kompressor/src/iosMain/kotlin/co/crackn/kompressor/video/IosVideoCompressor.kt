package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.naturalSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetExportPresetMediumQuality
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCancelled
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetExportSessionStatusFailed
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusFailed
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
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** iOS video compressor backed by [AVAssetReader] and [AVAssetWriter]. */
@OptIn(ExperimentalForeignApi::class)
internal class IosVideoCompressor : VideoCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        val startTime = CFAbsoluteTimeGetCurrent()
        onProgress(0f)
        val inputSize = nsFileSize(inputPath)

        if (canUseExportSession(config)) {
            IosVideoExportPipeline(inputPath, outputPath).execute(onProgress)
        } else {
            IosVideoTranscodePipeline(inputPath, outputPath, config).execute(onProgress)
        }

        onProgress(1f)
        val outputSize = nsFileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        CompressionResult(inputSize, outputSize, durationMs)
    }

    private fun canUseExportSession(config: VideoCompressionConfig): Boolean =
        config == VideoCompressionConfig()

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
        val sourceW = videoTrack.naturalSize.useContents { width.toInt() }
        val sourceH = videoTrack.naturalSize.useContents { height.toInt() }
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
        // Use the raw string key because kCVPixelBufferPixelFormatTypeKey (CFStringRef)
        // does not bridge to NSDictionary keys in K/N.
        val videoOutputSettings: Map<Any?, *> = mapOf(
            "PixelFormatType" to platform.CoreVideo.kCVPixelFormatType_32BGRA,
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
        AVVideoCodecKey to AVVideoCodecH264,
        AVVideoWidthKey to width,
        AVVideoHeightKey to height,
        AVVideoCompressionPropertiesKey to mapOf(
            AVVideoAverageBitRateKey to config.videoBitrate,
            AVVideoMaxKeyFrameIntervalKey to config.keyFrameInterval * config.maxFrameRate,
            AVVideoExpectedSourceFrameRateKey to config.maxFrameRate,
        ),
    )

    private fun startReaderWriter(reader: AVAssetReader, writer: AVAssetWriter) {
        check(reader.startReading()) {
            "AVAssetReader failed to start: ${reader.error?.localizedDescription}"
        }
        check(writer.startWriting()) {
            "AVAssetWriter failed to start: ${writer.error?.localizedDescription}"
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

    private suspend fun awaitWriterReady(writer: AVAssetWriter, input: AVAssetWriterInput) {
        var waited = 0L
        while (!input.readyForMoreMediaData) {
            check(writer.status != AVAssetWriterStatusFailed) {
                "AVAssetWriter failed: ${writer.error?.localizedDescription ?: "unknown"}"
            }
            check(waited < WRITER_READY_TIMEOUT_MS) {
                "AVAssetWriterInput not ready after ${waited}ms"
            }
            currentCoroutineContext().ensureActive()
            delay(WRITER_POLL_INTERVAL_MS)
            waited += WRITER_POLL_INTERVAL_MS
        }
    }

    private suspend fun finishWriting(
        reader: AVAssetReader,
        writer: AVAssetWriter,
        videoInput: AVAssetWriterInput,
        audioInput: AVAssetWriterInput?,
    ) {
        videoInput.markAsFinished()
        audioInput?.markAsFinished()
        checkReaderStatus(reader)
        awaitWriterFinish(writer)
        checkWriterCompleted(writer)
    }

    private fun checkReaderStatus(reader: AVAssetReader) {
        check(reader.status != AVAssetReaderStatusFailed) {
            "AVAssetReader failed: ${reader.error?.localizedDescription}"
        }
    }

    private suspend fun awaitWriterFinish(writer: AVAssetWriter) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { writer.cancelWriting() }
            writer.finishWritingWithCompletionHandler {
                if (writer.status == AVAssetWriterStatusCompleted) {
                    continuation.resume(Unit)
                } else {
                    val msg = writer.error?.localizedDescription ?: "unknown"
                    continuation.resumeWithException(
                        IllegalStateException("AVAssetWriter failed: $msg"),
                    )
                }
            }
        }
    }

    private fun checkWriterCompleted(writer: AVAssetWriter) {
        check(writer.status == AVAssetWriterStatusCompleted) {
            "AVAssetWriter not completed: ${writer.error?.localizedDescription}"
        }
    }

    private companion object {
        const val PROGRESS_SETUP = 0.05f
        const val PROGRESS_TRANSCODE_RANGE = 0.90f
        const val PROGRESS_REPORT_THRESHOLD = 0.01f
        const val WRITER_POLL_INTERVAL_MS = 10L
        const val WRITER_READY_TIMEOUT_MS = 10_000L
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
            val progressJob = launchProgressPoller(session, onProgress)
            try {
                awaitExport(session)
            } finally {
                progressJob.cancel()
            }
        }
    }

    private fun CoroutineScope.launchProgressPoller(
        session: AVAssetExportSession,
        onProgress: suspend (Float) -> Unit,
    ) = launch {
        while (isActive) {
            onProgress(session.progress)
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitExport(session: AVAssetExportSession) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { session.cancelExport() }
            session.exportAsynchronouslyWithCompletionHandler {
                when (session.status) {
                    AVAssetExportSessionStatusCompleted -> {
                        continuation.resume(Unit)
                    }
                    AVAssetExportSessionStatusFailed -> {
                        val msg = session.error?.localizedDescription ?: "unknown"
                        continuation.resumeWithException(
                            IllegalStateException("Export failed: $msg"),
                        )
                    }
                    AVAssetExportSessionStatusCancelled -> {
                        continuation.resumeWithException(
                            CancellationException("Export cancelled"),
                        )
                    }
                    else -> {
                        continuation.resumeWithException(
                            IllegalStateException("Unexpected export status: ${session.status}"),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val PROGRESS_POLL_INTERVAL_MS = 100L
    }
}
