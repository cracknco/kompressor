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
import java.io.FileNotFoundException
import java.io.IOException
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
 *  - Throws [AudioCompressionError.SourceNotFound] when [inputPath] can't be opened as a
 *    container at all (file missing, permission denied, corrupt header) — the extractor's
 *    `setDataSource` call is the first thing this routine does, so a typed error here keeps
 *    the `AudioCompressionError` hierarchy exhaustive for callers.
 *  - Throws [AudioCompressionError.NoAudioTrack] when the container opens cleanly but has
 *    zero tracks whose MIME starts with `audio/`. Surfaced BEFORE the codec is opened so
 *    callers don't pay the decoder init cost on an image / video-only input.
 *  - Throws [AudioCompressionError.UnsupportedSourceFormat] when the selected audio track is
 *    missing a MIME type, or reports a non-positive [MediaFormat.KEY_DURATION],
 *    [MediaFormat.KEY_SAMPLE_RATE], or [MediaFormat.KEY_CHANNEL_COUNT] — we can't bucket
 *    without these, and forcing callers to probe first would duplicate work.
 *  - Wraps other decoder failures with [AudioCompressionError.DecodingFailed].
 *  - Always releases the decoder and the extractor via `try/finally`, even on cancellation.
 *  - Cancellation: checked before each input/output poll so the suspend scope's cancellation
 *    propagates promptly.
 *
 * ## Concurrency
 *
 * This function holds no file-scope state — the PCM chunk buffer is a local var inside
 * [pumpDecoderIntoBucketer], so two coroutines calling `waveform(a)` and `waveform(b)` in
 * parallel on the same [AndroidAudioCompressor] instance do not race. Matches the
 * call-scoped buffer the iOS sibling has used since day one.
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
    val extractor = try {
        openAudioExtractor(inputPath)
    } catch (e: FileNotFoundException) {
        throw AudioCompressionError.SourceNotFound(
            "Source at $inputPath not found: ${e.message ?: "FileNotFoundException"}",
            cause = e,
        )
    } catch (e: IOException) {
        throw AudioCompressionError.IoFailed(
            "Failed to open source at $inputPath: ${e.message ?: "IOException"}",
            cause = e,
        )
    } catch (e: IllegalArgumentException) {
        // MediaExtractor.setDataSource throws IAE for malformed URIs / unsupported schemes.
        throw AudioCompressionError.SourceNotFound(
            "Source at $inputPath is not a readable media container: ${e.message ?: "IllegalArgumentException"}",
            cause = e,
        )
    }
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
        MediaCodec.createDecoderByType(mime)
    } catch (t: Throwable) {
        extractor.release()
        throw AudioCompressionError.DecodingFailed(
            "Failed to create decoder for $mime: ${t.message}",
            cause = t,
        )
    }
    // `createDecoderByType` returns an allocated native instance — if `configure` / `start`
    // subsequently throw (incompatible format, `CodecException`, hardware limits), we must
    // release `decoder` explicitly. An inline `.apply { ... }` block would leak the native
    // instance to `finalize()`, which is not guaranteed to run promptly under memory pressure
    // and surfaces as a codec leak on repeat `waveform()` calls against edge-case sources.
    try {
        decoder.configure(format, null, null, 0)
        decoder.start()
    } catch (t: Throwable) {
        decoder.safeRelease()
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
                    // Cap intermediate ticks just under 1f — a terminal COMPRESSING(1f) is
                    // emitted after the pump returns successfully so consumer progress UIs
                    // can reach 100% before the Result.success lands.
                    val fraction = (completed.toFloat() / targetSamples).coerceIn(0f, INTERMEDIATE_CAP)
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

    // Pump completed without throwing — emit the terminal 100% tick so consumer progress
    // bars don't visibly stall at 99.99% between the last intermediate tick and the
    // Result.success return.
    onProgress(CompressionProgress(CompressionProgress.Phase.COMPRESSING, 1f))
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
    // Call-scoped PCM chunk buffer: grown on demand, no shared state across concurrent
    // waveform() calls. Matches the iOS sibling's `reusableChunk` local.
    var reusableChunk = ByteArray(INITIAL_CHUNK_SIZE)

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
                        reusableChunk = feedBucketer(outputBuffer, bufferInfo, bucketer, reusableChunk)
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
 * Copy `bufferInfo.size` bytes out of [outputBuffer] (starting at [bufferInfo].offset) into
 * [reusableChunk], growing it when necessary, then hand the slice to [bucketer]. Copying is
 * unavoidable: the direct buffer backing `outputBuffer` is owned by the codec and must be
 * returned intact on [MediaCodec.releaseOutputBuffer] — but [reusableChunk] is scoped to the
 * enclosing pump call, so steady-state runs stay GC-quiet and concurrent callers never share
 * allocation state. Returns the (possibly grown) chunk array back to the caller.
 */
private fun feedBucketer(
    outputBuffer: java.nio.ByteBuffer,
    bufferInfo: MediaCodec.BufferInfo,
    bucketer: PcmPeakBucketer,
    reusableChunk: ByteArray,
): ByteArray {
    outputBuffer.position(bufferInfo.offset)
    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
    val chunk = if (reusableChunk.size >= bufferInfo.size) {
        reusableChunk
    } else {
        ByteArray(bufferInfo.size.takeHighestOneBit() shl 1)
    }
    outputBuffer.get(chunk, 0, bufferInfo.size)
    bucketer.accept(chunk, 0, bufferInfo.size)
    return chunk
}

// Tuned for typical MediaCodec AAC/MP3 access-unit outputs (~4-16 KB) while amortising the
// grow-to-max cost on the first big buffer. Not correctness-critical — the bucketer treats
// any size.
private const val INITIAL_CHUNK_SIZE = 16 * 1024

private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val MAX_PROGRESS_EMISSIONS = 64

// Intermediate progress ticks cap just under 1f; the terminal COMPRESSING(1f) is emitted by
// `extractAndroidWaveform` after the pump returns successfully.
private const val INTERMEDIATE_CAP = 0.9999f
