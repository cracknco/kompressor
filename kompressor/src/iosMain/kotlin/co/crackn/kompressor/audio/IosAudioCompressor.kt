/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import platform.AVFAudio.AVChannelLayoutKey
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
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create

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
        val inputSize = sizeOrTypedError(inputPath)
        // Pre-flight: reject obviously-empty inputs with a typed IO error so callers see the
        // same `IoFailed` subtype across platforms instead of the AVFoundation-specific
        // `fileFormatNotRecognized` → `UnsupportedSourceFormat` mapping an empty file would
        // otherwise trigger.
        if (inputSize == 0L) {
            throw AudioCompressionError.IoFailed("Input file is empty (0 bytes): $inputPath")
        }
        // Upfront configuration checks. Fail fast with a typed error for inputs iOS's encoder
        // cannot honour, rather than racing a generic `AVAssetWriterInput failed to append
        // sample buffer` from deep in the pipeline.
        // Bounds-check the audio track selection first so out-of-range indices produce the
        // documented typed error rather than being masked by downstream channel/bitrate checks
        // that implicitly target track 0.
        validateAudioTrackIndex(inputPath, config.audioTrackIndex)
        validateChannelConfiguration(inputPath, config)
        validateBitrateForSampleRateAndChannels(config)
        requireAacEncodingCapability(config)
        runPipelineWithTypedErrors(outputPath) {
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

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun sizeOrTypedError(path: String): Long =
        try {
            nsFileSize(path)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (typed: AudioCompressionError) {
            throw typed
        } catch (t: Throwable) {
            throw mapToAudioError(t)
        }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend inline fun runPipelineWithTypedErrors(outputPath: String, block: () -> Unit) {
        try {
            deletingOutputOnFailure(outputPath) { block() }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (typed: AudioCompressionError) {
            throw typed
        } catch (t: Throwable) {
            throw mapToAudioError(t)
        }
    }

    /**
     * Bounds-check [audioTrackIndex] against the source's audio track count so callers get a
     * typed [AudioCompressionError.UnsupportedSourceFormat] rather than racing an opaque
     * `AVAssetReader` / `AVAssetExportSession` failure. Probe failures from `AVURLAsset.tracks*`
     * are out-of-band: an unreadable file should propagate its own error from the real pipeline
     * rather than be re-typed here, and a `default-index` caller (`audioTrackIndex == 0`) on a
     * file that genuinely happens to have one audio track must not be blocked by a transient
     * probe glitch. We therefore only enforce the bounds when the probe yielded a usable count;
     * non-default indices on a probe-failed source still surface the real downstream error,
     * which carries more diagnostic context than a generic "probe failed" wrapper would.
     */
    private fun validateAudioTrackIndex(inputPath: String, audioTrackIndex: Int) {
        val count = AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
            .tracksWithMediaType(AVMediaTypeAudio)
            .size
        if (audioTrackIndex >= count) {
            throw AudioCompressionError.UnsupportedSourceFormat(
                "audioTrackIndex $audioTrackIndex out of bounds for $count audio track(s)",
            )
        }
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
     * [AudioCompressionError.UnsupportedBitrate].
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
    /**
     * Probe `AVAssetWriter.canApplyOutputSettings` with the AAC encoding dictionary
     * *before* creating `AVAssetWriterInput`. On real iOS devices the hardware AAC encoder
     * only supports mono/stereo; requesting ≥3 channels causes `AVAssetWriterInput.init`
     * to throw an Obj-C `NSException` that K/N cannot catch. This pre-flight turns the
     * crash into a typed [AudioCompressionError.UnsupportedConfiguration].
     */
    private fun requireAacEncodingCapability(config: AudioCompressionConfig) {
        val channelCount = config.channels.count
        if (channelCount <= 2) return
        val tmpUrl = NSURL.fileURLWithPath(
            NSTemporaryDirectory() + "kompressor-aac-probe-" +
                NSUUID().UUIDString + ".m4a",
        )
        val writer = AVAssetWriter.assetWriterWithURL(
            tmpUrl, fileType = AVFileTypeAppleM4A, error = null,
        )
        val probeSettings: Map<Any?, *> = buildMap {
            put(AVFormatIDKey, kAudioFormatMPEG4AAC)
            put(AVEncoderBitRateKey, config.bitrate)
            put(AVSampleRateKey, config.sampleRate)
            put(AVNumberOfChannelsKey, channelCount)
            channelLayoutData(config.channels)?.let { put(AVChannelLayoutKey, it) }
        }
        val supported = writer?.canApplyOutputSettings(
            probeSettings, forMediaType = AVMediaTypeAudio,
        ) ?: false
        if (!supported) {
            throw AudioCompressionError.UnsupportedConfiguration(
                "AAC encoder does not support $channelCount-channel output on this device",
            )
        }
    }

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
 * Surround (5.1 / 7.1) is rejected outright — Device Farm run 24536970778 (iPhone 13 /
 * A15 / iOS 18) confirmed AudioToolbox refuses every tested bitrate (32k–1280k) for ≥3
 * channels. Returns 0 for surround; `checkSupportedIosBitrate` converts that into
 * [AudioCompressionError.UnsupportedConfiguration].
 */
@Suppress("ThrowsCount")
internal fun checkSupportedIosBitrate(config: AudioCompressionConfig) {
    val maxBitrate = iosAacMaxBitrate(config.sampleRate, config.channels)
    if (maxBitrate == 0) {
        throw AudioCompressionError.UnsupportedConfiguration(
            "iOS AAC encoder does not support ${config.channels.count}-channel " +
                "(${config.channels.name}) output — empirically rejected at every bitrate " +
                "on A15 / iOS 18 (Device Farm run 24536970778). Use stereo or mono.",
        )
    }
    if (config.bitrate > maxBitrate) {
        throw AudioCompressionError.UnsupportedBitrate(
            "iOS AAC encoder does not support ${config.bitrate} bps at " +
                "${config.sampleRate} Hz × ${config.channels.count} channel(s); " +
                "max supported is $maxBitrate bps",
        )
    }
    val minBitrate = iosAacMinBitrate(config.sampleRate, config.channels)
    if (config.bitrate < minBitrate) {
        throw AudioCompressionError.UnsupportedBitrate(
            "iOS AAC encoder does not support ${config.bitrate} bps at " +
                "${config.sampleRate} Hz × ${config.channels.count} channel(s); " +
                "minimum supported is $minBitrate bps",
        )
    }
}

/**
 * Maximum bitrate (in bps) that AudioToolbox's AAC-LC encoder accepts for the given
 * [sampleRate] and [channels]. Returns 0 for surround (≥3 channels) — AudioToolbox rejects
 * multichannel AAC output at every bitrate on real hardware. See `docs/audio-bitrate-matrix.md`.
 */
internal fun iosAacMaxBitrate(sampleRate: Int, channels: AudioChannels): Int {
    if (channels.count > 2) return 0
    val maxPerChannelKbps = when {
        sampleRate <= IOS_AAC_LOW_RATE_HZ -> IOS_AAC_MAX_KBPS_LOW_RATE
        sampleRate <= IOS_AAC_MID_RATE_HZ -> IOS_AAC_MAX_KBPS_MID_RATE
        sampleRate <= IOS_AAC_HIGH_RATE_HZ -> IOS_AAC_MAX_KBPS_HIGH_RATE
        else -> IOS_AAC_MAX_KBPS_VERY_HIGH_RATE
    }
    return maxPerChannelKbps * IOS_KBPS_TO_BPS * channels.count
}

/**
 * Minimum bitrate (in bps) that AudioToolbox's AAC-LC encoder accepts for the given
 * [sampleRate] and [channels]. Returns 0 for surround (≥3 channels) — see [iosAacMaxBitrate].
 */
internal fun iosAacMinBitrate(sampleRate: Int, channels: AudioChannels): Int {
    if (channels.count > 2) return 0
    val minPerChannelKbps = when {
        sampleRate <= IOS_AAC_LOW_RATE_HZ -> IOS_AAC_MIN_KBPS_LOW_RATE
        sampleRate <= IOS_AAC_MID_RATE_HZ -> IOS_AAC_MIN_KBPS_MID_RATE
        else -> IOS_AAC_MIN_KBPS_HIGH_RATE
    }
    return minPerChannelKbps * IOS_KBPS_TO_BPS * channels.count
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

// AudioChannelLayout struct: mChannelLayoutTag (UInt32) + mChannelBitmap (UInt32) +
// mNumberChannelDescriptions (UInt32) = 12 bytes. When using a tag with zero descriptions,
// the variable-length mChannelDescriptions array is empty.
private const val AUDIO_CHANNEL_LAYOUT_STRUCT_SIZE = 12

@Suppress("MagicNumber")
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal fun channelLayoutData(channels: AudioChannels): NSData? {
    val tag: UInt = when (channels) {
        AudioChannels.MONO, AudioChannels.STEREO -> null
        AudioChannels.FIVE_POINT_ONE -> (121u shl 16) or 6u // kAudioChannelLayoutTag_MPEG_5_1_A
        AudioChannels.SEVEN_POINT_ONE -> (128u shl 16) or 8u // kAudioChannelLayoutTag_MPEG_7_1_C
    } ?: return null
    val bytes = ByteArray(AUDIO_CHANNEL_LAYOUT_STRUCT_SIZE)
    bytes[0] = (tag and 0xFFu).toByte()
    bytes[1] = ((tag shr 8) and 0xFFu).toByte()
    bytes[2] = ((tag shr 16) and 0xFFu).toByte()
    bytes[3] = ((tag shr 24) and 0xFFu).toByte()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPipeline(
    inputPath: String,
    outputPath: String,
    config: AudioCompressionConfig,
) {
    private val channelCount = config.channels.count
    private val audioTrackIndex = config.audioTrackIndex

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

    private val encodingSettings: Map<Any?, *> = buildMap {
        put(AVFormatIDKey, kAudioFormatMPEG4AAC)
        put(AVEncoderBitRateKey, config.bitrate)
        put(AVSampleRateKey, config.sampleRate)
        put(AVNumberOfChannelsKey, channelCount)
        channelLayoutData(config.channels)?.let { put(AVChannelLayoutKey, it) }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
        val audioTrack = audioTracks.getOrNull(audioTrackIndex) as? AVAssetTrack
            ?: throw AudioCompressionError.UnsupportedSourceFormat(
                "audioTrackIndex $audioTrackIndex out of bounds for ${audioTracks.size} audio track(s)",
            )

        val totalDurationSec = CMTimeGetSeconds(asset.duration)
        onProgress(PROGRESS_SETUP)
        currentCoroutineContext().ensureActive()

        val (reader, readerOutput) = createReader(audioTrack)
        val (writer, writerInput) = createWriter()

        try {
            startReaderWriter(reader, writer)
            onProgress(PROGRESS_READ_START)
            copySamples(readerOutput, writer, writerInput, totalDurationSec, onProgress)
            finishPipeline(reader, writer, writerInput)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            reader.cancelReading()
            writer.cancelWriting()
            throw e
        }
    }

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

    private suspend fun finishPipeline(
        reader: AVAssetReader,
        writer: AVAssetWriter,
        writerInput: AVAssetWriterInput,
    ) {
        writerInput.markAsFinished()
        if (reader.status == AVAssetReaderStatusFailed) {
            val err = reader.error
            if (err != null) {
                throw co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed")
            }
            error("AVAssetReader failed: unknown")
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
