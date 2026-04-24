/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import co.crackn.kompressor.AVNSErrorException
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.SafeLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

/**
 * iOS implementation of [AudioCompressor.waveform]: streams interleaved 16-bit signed LE PCM
 * via [AVAssetReader] + [AVAssetReaderTrackOutput] and reduces every chunk through
 * [PcmPeakBucketer].
 *
 * ## Why a dedicated extractor (not the existing [IosPipeline])
 *
 * [IosPipeline] combines reader + writer for compression. For waveform we only need the reader
 * side, but we also need to force the output into 16-bit signed PCM regardless of the source
 * codec. The decoding settings below explicitly request that layout — `AVFoundation` handles the
 * transparent conversion from MP3 / AAC / FLAC / Opus / WAV to PCM per the Apple AudioToolbox
 * documentation. No source sample rate is specified, so the reader emits PCM at the track's
 * native rate (e.g. 44.1 kHz for CD-quality sources). We read the sample rate and channel count
 * off [AVAudioFile.processingFormat] in a separate pre-flight — `AVAssetTrack.formatDescriptions`
 * would be the canonical path but its K/N binding is the `CMFormatDescriptionRef`-list bridge
 * that raises `ClassCastException` at runtime (see `IosFileUtils.readAudioMetadata` for the
 * full analysis). `AVAudioFile.processingFormat` exposes the same numbers via `AVAudioFormat`
 * without the bridge cast.
 *
 * ## Contract
 *
 *  - Throws [AudioCompressionError.SourceNotFound] when [inputPath] does not exist on-disk —
 *    `AVURLAsset` is a permissive constructor that never validates the underlying file, so
 *    we pre-check via [NSFileManager] to avoid the "file missing" case masquerading as
 *    `NoAudioTrack`.
 *  - Throws [AudioCompressionError.NoAudioTrack] when the asset opens but reports zero
 *    audio tracks.
 *  - Throws [AudioCompressionError.UnsupportedSourceFormat] when the asset's duration is not
 *    readable or non-positive.
 *  - Wraps reader start / copy failures with [AudioCompressionError.DecodingFailed].
 *  - `reader.cancelReading()` on every exit path (including cancellation) so native resources
 *    drop promptly.
 *
 * ## Memory footprint
 *
 * Each `CMSampleBuffer` delivered by [AVAssetReaderTrackOutput.copyNextSampleBuffer] carries a
 * single access unit of PCM — on AVFoundation this is typically a few KB to tens of KB. We copy
 * each buffer into a reusable heap [ByteArray] via [CMBlockBufferCopyDataBytes] (growing on
 * demand) and hand the slice to the bucketer. Steady-state allocations: zero.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
    "ThrowsCount",
    "LoopWithTooManyJumpStatements",
)
internal suspend fun extractIosWaveform(
    inputPath: String,
    targetSamples: Int,
    onProgress: suspend (CompressionProgress) -> Unit,
    logger: SafeLogger? = null,
): FloatArray {
    if (!NSFileManager.defaultManager.fileExistsAtPath(inputPath)) {
        throw AudioCompressionError.SourceNotFound(
            "No file at $inputPath",
        )
    }
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
    @Suppress("UNCHECKED_CAST")
    val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio) as List<AVAssetTrack>
    val audioTrack = audioTracks.firstOrNull()
        ?: throw AudioCompressionError.NoAudioTrack(
            "AVURLAsset at $inputPath has no track of type AVMediaType.audio",
        )

    val totalDurationSec = CMTimeGetSeconds(asset.duration)
    if (totalDurationSec.isNaN() || totalDurationSec <= 0.0) {
        throw AudioCompressionError.UnsupportedSourceFormat(
            "AVURLAsset has no usable duration (got $totalDurationSec seconds)",
        )
    }
    val totalDurationUs = (totalDurationSec * MICROS_PER_SECOND).toLong()
    val (sampleRate, channels) = readAudioFileFormat(inputPath, logger)

    // Decoding settings: interleaved 16-bit signed little-endian PCM. No AVSampleRateKey /
    // AVNumberOfChannelsKey — let AVFoundation pass through the native rate/channels so the
    // bucketer's sample-rate parameter matches what we're actually reading.
    val decodingSettings: Map<Any?, *> = mapOf(
        AVFormatIDKey to kAudioFormatLinearPCM,
        AVLinearPCMBitDepthKey to PCM_BIT_DEPTH,
        AVLinearPCMIsFloatKey to false,
        AVLinearPCMIsBigEndianKey to false,
        AVLinearPCMIsNonInterleaved to false,
    )

    val reader = AVAssetReader(asset = asset, error = null)
    val output = AVAssetReaderTrackOutput(track = audioTrack, outputSettings = decodingSettings)
    reader.addOutput(output)
    if (!reader.startReading()) {
        val err = reader.error
        val cause = err?.let { AVNSErrorException(it, "AVAssetReader failed to start") }
        throw AudioCompressionError.DecodingFailed(
            "AVAssetReader failed to start for $inputPath",
            cause = cause,
        )
    }

    val bucketer = PcmPeakBucketer(
        targetSamples = targetSamples,
        totalDurationUs = totalDurationUs,
        sampleRate = sampleRate,
        channels = channels,
    )
    val progressThrottle = maxOf(1, targetSamples / MAX_PROGRESS_EMISSIONS)
    var lastProgressTick = -1
    var reusableChunk = ByteArray(INITIAL_CHUNK_SIZE)

    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val sampleBuffer = output.copyNextSampleBuffer() ?: break
            try {
                val dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) ?: continue
                val totalSize = CMBlockBufferGetDataLength(dataBuffer).toInt()
                if (totalSize <= 0) continue
                if (reusableChunk.size < totalSize) {
                    reusableChunk = ByteArray(totalSize.takeHighestOneBit() shl 1)
                }
                reusableChunk.usePinned { pinned ->
                    CMBlockBufferCopyDataBytes(
                        theSourceBuffer = dataBuffer,
                        offsetToData = 0u,
                        dataLength = totalSize.toULong(),
                        destination = pinned.addressOf(0),
                    )
                }
                bucketer.accept(reusableChunk, 0, totalSize)
                val completed = bucketer.completedBucketCount
                val tick = completed / progressThrottle
                if (tick > lastProgressTick) {
                    lastProgressTick = tick
                    val fraction = (completed.toFloat() / targetSamples).coerceIn(0f, INTERMEDIATE_CAP)
                    onProgress(CompressionProgress(CompressionProgress.Phase.COMPRESSING, fraction))
                }
            } finally {
                CFRelease(sampleBuffer)
            }
        }
        if (reader.status == AVAssetReaderStatusFailed) {
            val err = reader.error
            throw AudioCompressionError.DecodingFailed(
                "AVAssetReader failed mid-read: ${err?.localizedDescription ?: "unknown"}",
                cause = err?.let { AVNSErrorException(it, "AVAssetReader failed") },
            )
        }
    } catch (t: Throwable) {
        // Cancellation and typed errors pass through; everything else re-types to DecodingFailed.
        when (t) {
            is AudioCompressionError -> throw t
            is kotlinx.coroutines.CancellationException -> throw t
            else -> throw AudioCompressionError.DecodingFailed(
                "Waveform PCM pump failed: ${t.message}",
                cause = t,
            )
        }
    } finally {
        reader.cancelReading()
    }

    // Pump completed without throwing — emit the terminal 100% tick so consumer progress
    // bars don't visibly stall at 99.99% between the last intermediate tick and the
    // Result.success return. Matches AndroidWaveformExtractor's behaviour.
    onProgress(CompressionProgress(CompressionProgress.Phase.COMPRESSING, 1f))
    return bucketer.finish()
}

/**
 * Read the native sample rate and channel count for the file at [inputPath] via
 * [AVAudioFile.processingFormat]. See the class-level KDoc for why this is preferred over
 * `AVAssetTrack.formatDescriptions` on K/N.
 *
 * Falls back to common defaults (44100 Hz / 2 ch) when the probe fails (unreadable file,
 * atypical container that [AVAudioFile] refuses to open). A misread here produces a
 * less-accurate waveform — the bucket time-to-frame mapping drifts by the ratio of
 * real-to-assumed rate — but never a crash or typed error. The real decoder
 * ([AVAssetReader]) runs separately and surfaces its own errors through the pump loop.
 *
 * When [logger] is supplied, probe failures (populated [NSError] or thrown Kotlin exception)
 * are emitted as `WARN` under [LogTags.AUDIO] so field-reported "the waveform has the wrong
 * shape" cases can be traced back to an [AVAudioFile] open failure instead of silently
 * slipping through.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Suppress("TooGenericExceptionCaught")
private fun readAudioFileFormat(inputPath: String, logger: SafeLogger?): Pair<Int, Int> {
    return try {
        memScoped {
            val errorRef = alloc<ObjCObjectVar<NSError?>>()
            val file = AVAudioFile(forReading = NSURL.fileURLWithPath(inputPath), error = errorRef.ptr)
            errorRef.value?.let { err ->
                logger?.warn(LogTags.AUDIO) {
                    "AVAudioFile probe populated NSError (domain=${err.domain} code=${err.code}): " +
                        (err.localizedDescription) +
                        " — falling back to ${DEFAULT_SAMPLE_RATE} Hz / $DEFAULT_CHANNEL_COUNT ch"
                }
            }
            val format = file.processingFormat
            val rate = format.sampleRate.toInt().takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
            val channels = format.channelCount.toInt().takeIf { it > 0 } ?: DEFAULT_CHANNEL_COUNT
            rate to channels
        }
    } catch (t: Throwable) {
        logger?.warn(LogTags.AUDIO, throwable = t) {
            "AVAudioFile probe threw — falling back to ${DEFAULT_SAMPLE_RATE} Hz / $DEFAULT_CHANNEL_COUNT ch"
        }
        DEFAULT_SAMPLE_RATE to DEFAULT_CHANNEL_COUNT
    }
}

private const val PCM_BIT_DEPTH = 16
private const val DEFAULT_SAMPLE_RATE = 44_100
private const val DEFAULT_CHANNEL_COUNT = 2

// Initial heap buffer for PCM chunks — grows on demand. CMSampleBuffers on AVFoundation are
// typically under 32 KB so one grow cycle usually takes us to steady state.
private const val INITIAL_CHUNK_SIZE = 32 * 1024

private const val MAX_PROGRESS_EMISSIONS = 64

// Intermediate progress ticks cap just under 1f; the terminal COMPRESSING(1f) is emitted by
// `extractIosWaveform` after the reader pump returns successfully.
private const val INTERMEDIATE_CAP = 0.9999f
private const val MICROS_PER_SECOND = 1_000_000.0
