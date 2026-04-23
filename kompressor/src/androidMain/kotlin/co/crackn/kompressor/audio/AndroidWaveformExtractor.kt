/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.safeInt
import co.crackn.kompressor.safeLong
import co.crackn.kompressor.safeRelease
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Android implementation of [AudioCompressor.waveform]: pumps PCM through [MediaExtractor] +
 * [MediaCodec] (sync mode) and reduces each chunk through [PcmPeakBucketer].
 *
 * Lives in its own file rather than inlined into [AndroidAudioCompressor] because the pump
 * state machine (input-queue loop + output-drain loop + EOS bridging) is already close to
 * detekt's `LongMethod` ceiling on its own — extracting it keeps the compressor's `compress`
 * path readable while giving this routine room to document the codec contract.
 *
 * ## Contract
 *
 *  - Throws [AudioCompressionError.NoAudioTrack] when [inputPath] has zero tracks whose MIME
 *    starts with `audio/`. Surfaced BEFORE the codec is opened so callers don't pay the decoder
 *    init cost on an image / video-only input.
 *  - Throws [AudioCompressionError.UnsupportedSourceFormat] when the extractor reports an
 *    unusable [MediaFormat.KEY_DURATION] (missing or non-positive) — we can't bucket without
 *    a duration, and forcing callers to probe first would duplicate work.
 *  - Wraps other decoder failures with [AudioCompressionError.DecodingFailed].
 *  - Always releases the decoder and the extractor via `try/finally`, even on cancellation.
 *  - Cancellation: checked before each input/output poll so the suspend scope's cancellation
 *    propagates promptly.
 *
 * ## Memory footprint
 *
 * No container-sized buffer is allocated. MediaCodec hands us per-access-unit [java.nio.ByteBuffer]s
 * whose backing size is driver-chosen (~64 KB on AOSP devices). The only bucketer-sized array is
 * the output [FloatArray] (`4 × targetSamples` bytes).
 */
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
    "ThrowsCount",
)
internal suspend fun extractAndroidWaveform(
    inputPath: String,
    targetSamples: Int,
    onProgress: suspend (CompressionProgress) -> Unit,
): FloatArray {
    val extractor = openAudioExtractor(inputPath)
    val trackIndex = findFirstAudioTrack(extractor)
    if (trackIndex == null) {
        extractor.release()
        throw AudioCompressionError.NoAudioTrack(
            "Source at $inputPath has no track with MIME prefix 'audio/'",
        )
    }
    extractor.selectTrack(trackIndex)
    val format = extractor.getTrackFormat(trackIndex)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
        extractor.release()
        throw AudioCompressionError.UnsupportedSourceFormat(
            "Selected audio track has no MIME type",
        )
    }
    val durationUs = format.safeLong(MediaFormat.KEY_DURATION)
    if (durationUs <= 0L) {
        extractor.release()
        throw AudioCompressionError.UnsupportedSourceFormat(
            "Selected audio track has no usable duration (got $durationUs µs)",
        )
    }
    val sampleRate = format.safeInt(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: run {
        extractor.release()
        throw AudioCompressionError.UnsupportedSourceFormat(
            "Selected audio track has no usable sample rate",
        )
    }
    val channels = format.safeInt(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 } ?: run {
        extractor.release()
        throw AudioCompressionError.UnsupportedSourceFormat(
            "Selected audio track has no usable channel count",
        )
    }

    val decoder = try {
        MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
    } catch (t: Throwable) {
        extractor.release()
        throw AudioCompressionError.DecodingFailed(
            "Failed to initialise decoder for $mime: ${t.message}",
            cause = t,
        )
    }

    val bucketer = PcmPeakBucketer(
        targetSamples = targetSamples,
        totalDurationUs = durationUs,
        sampleRate = sampleRate,
        channels = channels,
    )
    // Emit at most ~64 progress ticks across the whole source, regardless of targetSamples.
    // For targetSamples=200 this fires every 4 buckets; for 10_000 every 156. Keeps the
    // suspend callback cost bounded on very long / high-detail waveforms.
    val progressThrottle = maxOf(1, targetSamples / MAX_PROGRESS_EMISSIONS)
    var lastProgressTick = -1

    try {
        pumpDecoderIntoBucketer(
            extractor = extractor,
            decoder = decoder,
            bucketer = bucketer,
            emitProgress = { completed ->
                val tick = completed / progressThrottle
                if (tick > lastProgressTick) {
                    lastProgressTick = tick
                    // Cap at 0.9999f so the 1f terminal is the caller's to emit (or absent,
                    // since waveform has no FINALIZING_OUTPUT phase — the "done" signal is
                    // simply `Result.success`).
                    val fraction = (completed.toFloat() / targetSamples).coerceIn(0f, TERMINAL_CAP)
                    onProgress(CompressionProgress(CompressionProgress.Phase.COMPRESSING, fraction))
                }
            },
        )
    } catch (t: Throwable) {
        when (t) {
            is AudioCompressionError -> throw t
            is kotlinx.coroutines.CancellationException -> throw t
            else -> throw AudioCompressionError.DecodingFailed(
                "PCM pump failed: ${t.message}",
                cause = t,
            )
        }
    } finally {
        decoder.safeRelease()
        extractor.release()
    }

    return bucketer.finish()
}

/** First track whose MIME starts with `audio/`, or `null` when the container has no audio. */
private fun findFirstAudioTrack(extractor: MediaExtractor): Int? {
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith("audio/")) return i
    }
    return null
}

/**
 * Sync-mode MediaCodec pump. Returns when the output side reports EOS or the coroutine is
 * cancelled. Throws anything the codec surfaces; the caller is responsible for wrapping.
 *
 * The pump uses sync mode (not async callbacks) because the bucketer is naturally pull-shaped
 * and async callbacks would require a channel between the codec thread and the suspending
 * caller — extra complexity for no throughput benefit on a decode-only pipeline.
 */
@Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod", "DEPRECATION")
private suspend fun pumpDecoderIntoBucketer(
    extractor: MediaExtractor,
    decoder: MediaCodec,
    bucketer: PcmPeakBucketer,
    emitProgress: suspend (completedBuckets: Int) -> Unit,
) {
    val bufferInfo = MediaCodec.BufferInfo()
    var inputEos = false
    var outputEos = false

    while (!outputEos) {
        currentCoroutineContext().ensureActive()

        // ── Input side: feed extractor samples into the decoder's input buffers. ───────────
        if (!inputEos) {
            val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                    ?: error("decoder returned index $inputIndex but no input buffer")
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    decoder.queueInputBuffer(
                        inputIndex, 0, 0, 0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputEos = true
                } else {
                    decoder.queueInputBuffer(
                        inputIndex, 0, sampleSize, extractor.sampleTime, 0,
                    )
                    extractor.advance()
                }
            }
            // INFO_TRY_AGAIN_LATER: the decoder has no free input buffer yet; retry on the
            // next iteration after the output side drains. No action here.
        }

        // ── Output side: drain decoded PCM into the bucketer. ─────────────────────────────
        when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // No output yet — loop back to fill more input.
            }
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // PCM format finalised. We already seeded the bucketer with the input-track
                // sample rate / channels, which match the decoder's PCM output for the
                // Android audio codecs we support (AAC, MP3, Opus, FLAC, Vorbis — all
                // decode to PCM at the track's native rate/channels). Nothing to do.
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                // Deprecated since API 21 — `getOutputBuffer(index)` transparently handles
                // buffer rotation. Safe to ignore on our `minSdk` = 24.
            }
            else -> {
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        feedBucketer(outputBuffer, bufferInfo, bucketer)
                        emitProgress(bucketer.completedBucketCount)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
            }
        }
    }
}

/**
 * Copy `bufferInfo.size` bytes out of [outputBuffer] (starting at [bufferInfo].offset) into a
 * reusable heap array and hand that slice to [bucketer]. Copying is unavoidable: the direct
 * buffer backing `outputBuffer` is owned by the codec and must be returned intact on
 * [MediaCodec.releaseOutputBuffer] — but we only allocate once via the `ThreadLocal`-style
 * reusable array, so steady-state runs stay GC-quiet.
 */
private fun feedBucketer(
    outputBuffer: java.nio.ByteBuffer,
    bufferInfo: MediaCodec.BufferInfo,
    bucketer: PcmPeakBucketer,
) {
    outputBuffer.position(bufferInfo.offset)
    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
    val chunk = acquireChunkBuffer(bufferInfo.size)
    outputBuffer.get(chunk, 0, bufferInfo.size)
    bucketer.accept(chunk, 0, bufferInfo.size)
}

/**
 * Return a `ByteArray` of at least [size] bytes, reusing the lazily-allocated field buffer
 * when possible. Grows monotonically (by doubling) so a single long waveform call re-uses
 * the same allocation across thousands of decoder-output cycles.
 */
private fun acquireChunkBuffer(size: Int): ByteArray {
    val current = chunkBufferRef
    if (current != null && current.size >= size) return current
    val grown = ByteArray(maxOf(INITIAL_CHUNK_SIZE, size.takeHighestOneBit() shl 1))
    chunkBufferRef = grown
    return grown
}

// Waveform extraction is expected to run one call at a time on a single suspend scope, so a
// plain top-level var is safe. If this ever becomes multi-threaded a ThreadLocal would be the
// right cure; the concurrent use-case is `kompressor.audio.waveform(a) + waveform(b)` from two
// coroutines which is not a pattern we optimise for today.
@Volatile
private var chunkBufferRef: ByteArray? = null

// Tuned for typical MediaCodec AAC/MP3 access-unit outputs (~4-16 KB) while amortising the
// grow-to-max cost on the first big buffer. Not correctness-critical — the bucketer treats
// any size.
private const val INITIAL_CHUNK_SIZE = 16 * 1024

private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val MAX_PROGRESS_EMISSIONS = 64
private const val TERMINAL_CAP = 0.9999f
