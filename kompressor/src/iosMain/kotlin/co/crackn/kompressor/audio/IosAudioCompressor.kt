package co.crackn.kompressor.audio

import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetExportPresetAppleM4A
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
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVFileTypeAppleM4A
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** iOS audio compressor backed by [AVAssetReader] and [AVAssetWriter]. */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioCompressor : AudioCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        require(config.codec == AudioCodec.AAC) { "Only AAC codec is currently supported" }
        val startTime = CFAbsoluteTimeGetCurrent()
        onProgress(0f)
        val inputSize = nsFileSize(inputPath)

        if (canUseExportSession(config)) {
            IosExportSessionPipeline(inputPath, outputPath).execute(onProgress)
        } else {
            IosPipeline(inputPath, outputPath, config).execute(onProgress)
        }

        onProgress(1f)
        val outputSize = nsFileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        CompressionResult(inputSize, outputSize, durationMs)
    }

    // AVAssetExportSession uses Apple's internal preset quality — it does NOT honour
    // the exact bitrate/sampleRate/channels from AudioCompressionConfig. We only use it
    // when the caller passes the default config (no custom expectations to violate).
    private fun canUseExportSession(config: AudioCompressionConfig): Boolean =
        config == AudioCompressionConfig()

    private companion object {
        const val MILLIS_PER_SEC = 1000.0
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPipeline(
    inputPath: String,
    outputPath: String,
    config: AudioCompressionConfig,
) {
    private val channelCount =
        if (config.channels == AudioChannels.MONO) 1 else 2

    private val inputUrl = NSURL.fileURLWithPath(inputPath)
    private val outputUrl = NSURL.fileURLWithPath(outputPath)
    private val asset = AVURLAsset(uRL = inputUrl, options = null)

    private val decodingSettings: Map<Any?, *> = mapOf(
        AVFormatIDKey to kAudioFormatLinearPCM,
        AVLinearPCMBitDepthKey to PCM_BIT_DEPTH,
        AVLinearPCMIsFloatKey to false,
        AVLinearPCMIsBigEndianKey to false,
        AVLinearPCMIsNonInterleaved to false,
        AVSampleRateKey to config.sampleRate,
        AVNumberOfChannelsKey to channelCount,
    )

    private val encodingSettings: Map<Any?, *> = mapOf(
        AVFormatIDKey to kAudioFormatMPEG4AAC,
        AVEncoderBitRateKey to config.bitrate,
        AVSampleRateKey to config.sampleRate,
        AVNumberOfChannelsKey to channelCount,
    )

    @Suppress("UNCHECKED_CAST")
    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        val audioTrack = asset.tracksWithMediaType(AVMediaTypeAudio)
            .firstOrNull() as? AVAssetTrack
            ?: throw IllegalArgumentException("No audio track found in input file")

        val totalDurationSec = CMTimeGetSeconds(asset.duration)
        onProgress(PROGRESS_SETUP)
        currentCoroutineContext().ensureActive()

        val (reader, readerOutput) = createReader(audioTrack)
        val (writer, writerInput) = createWriter()

        try {
            check(reader.startReading()) {
                "AVAssetReader failed to start: ${reader.error?.localizedDescription}"
            }
            check(writer.startWriting()) {
                "AVAssetWriter failed to start: ${writer.error?.localizedDescription}"
            }
            writer.startSessionAtSourceTime(CMTimeMake(value = 0, timescale = 1))
            onProgress(PROGRESS_READ_START)

            copySamples(readerOutput, writerInput, totalDurationSec, onProgress)
            writerInput.markAsFinished()
            checkReaderStatus(reader)
            awaitWriterFinish(writer)
            checkWriterCompleted(writer)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            reader.cancelReading()
            writer.cancelWriting()
            throw e
        }
    }

    private fun createReader(
        audioTrack: AVAssetTrack,
    ): Pair<AVAssetReader, AVAssetReaderTrackOutput> {
        val reader = AVAssetReader(asset = asset, error = null)
        val output = AVAssetReaderTrackOutput(
            track = audioTrack,
            outputSettings = decodingSettings,
        )
        reader.addOutput(output)
        return reader to output
    }

    private fun createWriter(): Pair<AVAssetWriter, AVAssetWriterInput> {
        val writer = AVAssetWriter.assetWriterWithURL(
            outputUrl, fileType = AVFileTypeAppleM4A, error = null,
        ) ?: error("Failed to create AVAssetWriter for: $outputUrl")
        val input = AVAssetWriterInput.assetWriterInputWithMediaType(
            mediaType = AVMediaTypeAudio,
            outputSettings = encodingSettings,
        )
        input.expectsMediaDataInRealTime = false
        writer.addInput(input)
        return writer to input
    }

    private suspend fun copySamples(
        readerOutput: AVAssetReaderTrackOutput,
        writerInput: AVAssetWriterInput,
        totalDurationSec: Double,
        onProgress: suspend (Float) -> Unit,
    ) {
        var lastReported = PROGRESS_READ_START
        while (true) {
            currentCoroutineContext().ensureActive()
            val buffer = readerOutput.copyNextSampleBuffer() ?: break
            try {
                lastReported = reportSampleProgress(
                    buffer, totalDurationSec, lastReported, onProgress,
                )
                writerInput.appendSampleBuffer(buffer)
            } finally {
                CFRelease(buffer)
            }
        }
    }

    private suspend fun reportSampleProgress(
        buffer: platform.CoreMedia.CMSampleBufferRef,
        totalDurationSec: Double,
        lastReported: Float,
        onProgress: suspend (Float) -> Unit,
    ): Float {
        if (totalDurationSec <= 0) return lastReported
        val timestamp = CMSampleBufferGetPresentationTimeStamp(buffer)
        val sampleTime = CMTimeGetSeconds(timestamp)
        val fraction = (sampleTime / totalDurationSec).coerceIn(0.0, 1.0).toFloat()
        val progress = PROGRESS_READ_START + PROGRESS_TRANSCODE_RANGE * fraction
        val shouldReport = progress - lastReported >= PROGRESS_REPORT_THRESHOLD
        if (shouldReport) onProgress(progress)
        return if (shouldReport) progress else lastReported
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
        const val PCM_BIT_DEPTH = 16
        const val PROGRESS_SETUP = 0.05f
        const val PROGRESS_READ_START = 0.10f
        const val PROGRESS_TRANSCODE_RANGE = 0.85f
        const val PROGRESS_REPORT_THRESHOLD = 0.01f
    }
}

/** Uses [AVAssetExportSession] for hardware-accelerated compression with default AAC settings. */
@OptIn(ExperimentalForeignApi::class)
private class IosExportSessionPipeline(
    inputPath: String,
    private val outputPath: String,
) {
    private val inputUrl = NSURL.fileURLWithPath(inputPath)
    private val outputUrl = NSURL.fileURLWithPath(outputPath)

    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        val asset = AVURLAsset(uRL = inputUrl, options = null)
        val session = AVAssetExportSession.exportSessionWithAsset(
            asset = asset,
            presetName = AVAssetExportPresetAppleM4A,
        ) ?: error("AVAssetExportSession not available for input")
        session.outputURL = outputUrl
        session.outputFileType = AVFileTypeAppleM4A

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
