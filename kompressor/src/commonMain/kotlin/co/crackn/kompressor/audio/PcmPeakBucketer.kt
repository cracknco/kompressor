/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import kotlin.math.abs

/**
 * Streams 16-bit signed little-endian interleaved PCM chunks and reduces them into a fixed
 * number of peak buckets, following the mobile-UI "absolute peaks" convention documented on
 * [AudioCompressor.waveform].
 *
 * ## Memory invariant
 *
 * The bucketer holds exactly:
 *  - one [FloatArray] of size [targetSamples] (the output peaks), and
 *  - a handful of [Long] / [Int] counters.
 *
 * No input buffer grows with the source duration — callers push chunks of bounded size (≤ 64 KB
 * on both Android and iOS) and the bucketer's state footprint is `O(targetSamples)`, **not**
 * `O(audio duration)`. A 1-hour podcast with `targetSamples = 200` uses ~800 B of state plus
 * the caller's own chunk buffer.
 *
 * ## Peak formula
 *
 * For every 16-bit sample in the stream the bucketer computes
 * `normalized = abs(sample) / 32_768f` — the denominator is the absolute-value full-scale for a
 * signed 16-bit sample (`2^15 = 32_768`). Using `32_768` rather than `32_767` means a
 * maximally-negative sample (`-32768`) maps to exactly `1f`, which matches what all the mobile
 * waveform libraries do and keeps `peaks[i] <= 1f` as a hard invariant.
 *
 * Per-sample (not per-frame) peak reduction is deliberate: the result is identical to per-frame
 * reduction when the renderer's goal is "the loudest sample in this time slice", and avoids
 * paying the multi-channel bookkeeping cost on every sample.
 *
 * ## Bucket assignment
 *
 * Each sample's bucket is `(frameTimeUs * targetSamples / totalDurationUs)` clamped into
 * `[0, targetSamples - 1]`, where `frameTimeUs = framesProcessed * 1_000_000 / sampleRate`. A
 * frame — *one sample per channel* — advances the frame counter once; this ties bucket width to
 * wall-clock time independently of channel count, so the same source converted to mono vs. stereo
 * produces the same waveform shape.
 *
 * @property targetSamples number of output buckets the caller requested. Must be positive.
 * @property totalDurationUs expected total duration of the source in microseconds. Must be
 *   positive — an unknown duration should surface to the caller before the bucketer is
 *   constructed (e.g. by rejecting the waveform call with a typed error).
 */
internal class PcmPeakBucketer(
    val targetSamples: Int,
    private val totalDurationUs: Long,
    private val sampleRate: Int,
    private val channels: Int,
) {

    init {
        require(targetSamples > 0) { "targetSamples must be positive, was $targetSamples" }
        require(totalDurationUs > 0) { "totalDurationUs must be positive, was $totalDurationUs" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels > 0) { "channels must be positive, was $channels" }
    }

    private val peaks = FloatArray(targetSamples)
    private var currentBucketIndex = 0
    private var currentBucketPeak = 0f
    private var framesProcessed = 0L
    private var samplesWithinFrame = 0

    /**
     * Completed-bucket count so far. A "completed" bucket is one whose peak has been written
     * to the output array; the bucket currently being accumulated is **not** counted. Useful
     * for progress throttling.
     */
    val completedBucketCount: Int get() = currentBucketIndex

    /**
     * Consume [sizeBytes] bytes of interleaved 16-bit signed little-endian PCM starting at
     * [offsetBytes] inside [buffer]. Silently ignores a dangling final byte if `sizeBytes` is
     * odd — partial samples can never happen in practice (decoders emit whole samples), but
     * being defensive avoids an `ArrayIndexOutOfBoundsException` surfacing as a generic
     * "waveform failed" error.
     */
    fun accept(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int) {
        require(offsetBytes >= 0) { "offsetBytes must be non-negative, was $offsetBytes" }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative, was $sizeBytes" }
        require(offsetBytes + sizeBytes <= buffer.size) {
            "offsetBytes + sizeBytes ($offsetBytes + $sizeBytes) exceeds buffer.size (${buffer.size})"
        }
        val endExclusive = offsetBytes + (sizeBytes and PAIRED_SIZE_MASK)
        var i = offsetBytes
        while (i < endExclusive) {
            // Little-endian 16-bit signed: low byte first, then high byte (sign-extended via `.toInt()`).
            val lo = buffer[i].toInt() and BYTE_MASK
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl BYTE_BITS) or lo
            val normalized = abs(sample) / PCM16_FULL_SCALE
            if (normalized > currentBucketPeak) currentBucketPeak = normalized

            samplesWithinFrame++
            if (samplesWithinFrame >= channels) {
                samplesWithinFrame = 0
                framesProcessed++
                val targetBucket = bucketIndexForCurrentFrame()
                if (targetBucket > currentBucketIndex) {
                    // The current frame has crossed into a later bucket — flush the pending peak
                    // for the old bucket and advance. If the jump spans multiple buckets (e.g. a
                    // sparse stream with uneven timestamps), the intervening buckets stay at 0f,
                    // which is the correct reading for "no samples covered this time slice".
                    flushPendingBucket()
                    currentBucketIndex = targetBucket
                }
            }
            i += 2
        }
    }

    /**
     * Commit any pending bucket and return a right-sized [FloatArray]. After [finish] the
     * bucketer should not be reused.
     *
     * The returned array may be **shorter** than [targetSamples] when the source was shorter
     * than one bucket's duration (very short clips, truncated files). Consumers must tolerate
     * this — see the [AudioCompressor.waveform] contract.
     */
    fun finish(): FloatArray {
        if (framesProcessed == 0L) {
            // No audio data seen at all — return an empty array rather than a FloatArray of
            // zeroes that would claim "we measured silence". Matches the interface's
            // "may be shorter than targetSamples" contract at its degenerate end.
            return FloatArray(0)
        }
        // Flush the still-accumulating bucket. We always commit it — even if its peak is 0f —
        // because frames were assigned to it, and dropping the trailing bucket would silently
        // undercut the returned length.
        if (currentBucketIndex < targetSamples) {
            peaks[currentBucketIndex] = currentBucketPeak
            currentBucketIndex++
        }
        currentBucketPeak = 0f
        return peaks.copyOf(currentBucketIndex.coerceAtMost(targetSamples))
    }

    private fun flushPendingBucket() {
        if (currentBucketIndex < targetSamples) {
            peaks[currentBucketIndex] = currentBucketPeak
        }
        currentBucketPeak = 0f
    }

    private fun bucketIndexForCurrentFrame(): Int {
        // `framesProcessed * 1_000_000` must stay in Long range: at 48 kHz the product equals
        // `framesProcessed * 1e6`, overflow would require ≈ 2^63 / (48_000 * 1e6) ≈ 1.9e8
        // seconds ≈ 6 years of continuous audio. Not a realistic input.
        val frameTimeUs = framesProcessed * MICROS_PER_SECOND / sampleRate
        val index = (frameTimeUs * targetSamples / totalDurationUs).toInt()
        return index.coerceIn(0, targetSamples - 1)
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8

        // Full-scale for 16-bit signed PCM: 2^15. Using this (not 32_767) means -32768 maps to
        // exactly 1f, and the invariant `peaks[i] <= 1f` holds for every representable sample.
        const val PCM16_FULL_SCALE = 32_768f

        const val MICROS_PER_SECOND = 1_000_000L

        // `(size & -2)` truncates odd sizes by 1 — a dangling final byte is silently skipped.
        const val PAIRED_SIZE_MASK = -2
    }
}
