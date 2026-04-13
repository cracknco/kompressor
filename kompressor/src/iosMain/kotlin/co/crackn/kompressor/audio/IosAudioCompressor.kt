package co.crackn.kompressor.audio

import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.awaitExportSession
import co.crackn.kompressor.awaitWriterFinish
import co.crackn.kompressor.awaitWriterReady
import co.crackn.kompressor.checkWriterCompleted
import co.crackn.kompressor.deletingOutputOnFailure
import co.crackn.kompressor.nsFileSize
import co.crackn.kompressor.suspendRunCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetExportPresetAppleM4A
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFAudio.AVAudioFile
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
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL

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

        // Upfront configuration checks. Fail fast with a typed error for inputs iOS's encoder
        // cannot honour, rather than racing a generic `AVAssetWriterInput failed to append
        // sample buffer` from deep in the pipeline.
        validateChannelConfiguration(inputPath, config)
        validateBitrateForSampleRateAndChannels(config)

        deletingOutputOnFailure(outputPath) {
            if (canUseExportSession(config)) {
                IosExportSessionPipeline(inputPath, outputPath).execute(onProgress)
            } else {
                IosPipeline(inputPath, outputPath, config).execute(onProgress)
            }
        }

        onProgress(1f)
        val outputSize = nsFileSize(outputPath)
        val durationMs = ((CFAbsoluteTimeGetCurrent() - startTime) * MILLIS_PER_SEC).toLong()
        CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Reject configurations iOS's audio pipeline cannot honour. Currently the only case is
     * upmixing (source channel count < requested channel count) — iOS's `AVAssetReaderTrackOutput`
     * with `AVNumberOfChannelsKey=2` does not duplicate a mono track into stereo.
     *
     * Probe failures are non-fatal: we don't want to block compression on an unreadable-by-probe
     * file that the real pipeline may still handle. The underlying pipeline will surface a
     * proper error if the file is truly unreadable.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun validateChannelConfiguration(inputPath: String, config: AudioCompressionConfig) {
        val sourceChannels = try {
            AVAudioFile(forReading = NSURL.fileURLWithPath(inputPath), error = null)
                .processingFormat.channelCount.toInt()
        } catch (_: Throwable) {
            return
        }
        if (sourceChannels < config.channels.count) {
            throw AudioCompressionError.UnsupportedConfiguration(
                "iOS cannot upmix a $sourceChannels-channel source into " +
                    "${config.channels.count}-channel output",
            )
        }
    }

    /**
     * iOS's AAC-LC encoder (`AudioToolbox`'s `AACEncoder`, wired through `AVAssetWriterInput`)
     * rejects bitrates above a per-sample-rate / per-channel ceiling. Crossing the ceiling
     * surfaces as an opaque `AVAssetWriterInput.appendSampleBuffer` failure mid-export. Rather
     * than let callers chase that, we reject the configuration upfront with a typed
     * [AudioCompressionError.UnsupportedConfiguration].
     *
     * The per-channel caps below were determined empirically by binary-searching the property
     * test (see `AudioCompressionPropertyTest`). The table mirrors Apple's published AAC-LC
     * ranges in the AudioToolbox documentation:
     *
     * | sample rate     | max kbps / channel |
     * |-----------------|-------------------:|
     * | ≤ 24 kHz        |                 64 |
     * | ≤ 32 kHz        |                 96 |
     * | ≤ 44.1 kHz      |                160 |
     * | > 44.1 kHz      |                192 |
     */
    private fun validateBitrateForSampleRateAndChannels(config: AudioCompressionConfig) {
        checkSupportedIosBitrate(config)
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

/**
 * Pure, iOS-only validation of the bitrate / sample-rate / channel combinations Apple's AAC-LC
 * encoder (reached via `AVAssetWriterInput`) will actually honour end-to-end. Exposed
 * `internal` so the table is covered directly by `IosAudioBitrateValidationTest` instead of
 * relying on the property test bumping into each edge case.
 *
 * Per-channel caps derived from Apple's AudioToolbox AAC-LC documentation and empirically
 * confirmed by property-test shrinks (32 kHz mono @ 131 kbps, 22.05 kHz stereo @ 145 kbps
 * both reproduce the opaque "failed to append sample buffer" above their respective cap).
 */
internal fun checkSupportedIosBitrate(config: AudioCompressionConfig) {
    val maxPerChannel = when {
        config.sampleRate <= IOS_AAC_LOW_RATE_HZ -> IOS_AAC_MAX_KBPS_LOW_RATE
        config.sampleRate <= IOS_AAC_MID_RATE_HZ -> IOS_AAC_MAX_KBPS_MID_RATE
        config.sampleRate <= IOS_AAC_HIGH_RATE_HZ -> IOS_AAC_MAX_KBPS_HIGH_RATE
        else -> IOS_AAC_MAX_KBPS_VERY_HIGH_RATE
    }
    val maxBitrate = maxPerChannel * IOS_KBPS_TO_BPS * config.channels.count
    if (config.bitrate > maxBitrate) {
        throw AudioCompressionError.UnsupportedConfiguration(
            "iOS AAC encoder does not support ${config.bitrate} bps at " +
                "${config.sampleRate} Hz × ${config.channels.count} channel(s); " +
                "max supported is $maxBitrate bps",
        )
    }
    // Apple's AAC-LC encoder also rejects bitrates below a per-sample-rate **minimum**.
    // The surrounding failure mode is identical to the over-cap case ("failed to append
    // sample buffer") so we fail fast with the same typed error. Minimums empirically
    // determined from Apple AudioToolbox AAC-LC parameters and confirmed by property-test
    // shrinks (32 kHz stereo @ 42 kbps fails; 32 kHz stereo @ 48 kbps succeeds).
    val minPerChannel = when {
        config.sampleRate <= IOS_AAC_LOW_RATE_HZ -> IOS_AAC_MIN_KBPS_LOW_RATE
        config.sampleRate <= IOS_AAC_MID_RATE_HZ -> IOS_AAC_MIN_KBPS_MID_RATE
        else -> IOS_AAC_MIN_KBPS_HIGH_RATE
    }
    val minBitrate = minPerChannel * IOS_KBPS_TO_BPS * config.channels.count
    if (config.bitrate < minBitrate) {
        throw AudioCompressionError.UnsupportedConfiguration(
            "iOS AAC encoder does not support ${config.bitrate} bps at " +
                "${config.sampleRate} Hz × ${config.channels.count} channel(s); " +
                "minimum supported is $minBitrate bps",
        )
    }
}

private const val IOS_KBPS_TO_BPS = 1_000
private const val IOS_AAC_LOW_RATE_HZ = 24_000
private const val IOS_AAC_MID_RATE_HZ = 32_000
private const val IOS_AAC_HIGH_RATE_HZ = 44_100
private const val IOS_AAC_MAX_KBPS_LOW_RATE = 64
private const val IOS_AAC_MAX_KBPS_MID_RATE = 96
private const val IOS_AAC_MAX_KBPS_HIGH_RATE = 160
private const val IOS_AAC_MAX_KBPS_VERY_HIGH_RATE = 192
private const val IOS_AAC_MIN_KBPS_LOW_RATE = 16
private const val IOS_AAC_MIN_KBPS_MID_RATE = 24
private const val IOS_AAC_MIN_KBPS_HIGH_RATE = 32

@OptIn(ExperimentalForeignApi::class)
private class IosPipeline(
    inputPath: String,
    outputPath: String,
    config: AudioCompressionConfig,
) {
    private val channelCount = config.channels.count

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

            copySamples(readerOutput, writer, writerInput, totalDurationSec, onProgress)
            finishPipeline(reader, writer, writerInput)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            reader.cancelReading()
            writer.cancelWriting()
            throw e
        }
    }

    private suspend fun finishPipeline(
        reader: AVAssetReader,
        writer: AVAssetWriter,
        writerInput: AVAssetWriterInput,
    ) {
        writerInput.markAsFinished()
        check(reader.status != AVAssetReaderStatusFailed) {
            "AVAssetReader failed: ${reader.error?.localizedDescription}"
        }
        awaitWriterFinish(writer)
        checkWriterCompleted(writer)
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
        writer: AVAssetWriter,
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
                awaitWriterReady(writer, writerInput)
                check(writerInput.appendSampleBuffer(buffer)) {
                    "AVAssetWriterInput failed to append sample buffer"
                }
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
                awaitExportSession(session)
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

    private companion object {
        const val PROGRESS_POLL_INTERVAL_MS = 100L
    }
}
