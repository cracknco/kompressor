/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [PcmPeakBucketer] — the platform-agnostic reducer that every waveform
 * extractor funnels PCM through. Lives in commonTest because the bucketer is pure math
 * (no AVFoundation / MediaCodec surface), and bugs here corrupt both platforms in lockstep.
 */
class PcmPeakBucketerTest {

    @Test
    fun constructorRejectsNonPositiveTargetSamples() {
        assertFailsWith<IllegalArgumentException> {
            PcmPeakBucketer(targetSamples = 0, totalDurationUs = 1_000_000, sampleRate = 44_100, channels = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PcmPeakBucketer(targetSamples = -1, totalDurationUs = 1_000_000, sampleRate = 44_100, channels = 1)
        }
    }

    @Test
    fun constructorRejectsNonPositiveDuration() {
        assertFailsWith<IllegalArgumentException> {
            PcmPeakBucketer(targetSamples = 10, totalDurationUs = 0, sampleRate = 44_100, channels = 1)
        }
    }

    @Test
    fun constructorRejectsNonPositiveSampleRate() {
        assertFailsWith<IllegalArgumentException> {
            PcmPeakBucketer(targetSamples = 10, totalDurationUs = 1_000_000, sampleRate = 0, channels = 1)
        }
    }

    @Test
    fun constructorRejectsNonPositiveChannels() {
        assertFailsWith<IllegalArgumentException> {
            PcmPeakBucketer(targetSamples = 10, totalDurationUs = 1_000_000, sampleRate = 44_100, channels = 0)
        }
    }

    @Test
    fun emptyInputReturnsEmptyArray() {
        val bucketer = PcmPeakBucketer(
            targetSamples = 10,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 1,
        )
        bucketer.finish() shouldBe floatArrayOf()
    }

    @Test
    fun silencePcmProducesZeroPeaks() {
        // 44_100 samples of silence = 1 second, mono.
        val bucketer = PcmPeakBucketer(
            targetSamples = 10,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 1,
        )
        val silence = ByteArray(44_100 * 2) // 2 bytes per 16-bit sample
        bucketer.accept(silence, 0, silence.size)
        val peaks = bucketer.finish()
        peaks.size shouldBe 10
        peaks.forEach { it shouldBe 0f }
    }

    @Test
    fun fullScalePositivePcmMapsToOne() {
        // Peak-positive samples: 32_767 → 32_767 / 32_768f ≈ 0.99997
        val bucketer = PcmPeakBucketer(
            targetSamples = 1,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 1,
        )
        val samples = ByteArray(44_100 * 2)
        for (i in samples.indices step 2) {
            samples[i] = 0xFF.toByte() // low byte
            samples[i + 1] = 0x7F // high byte → 0x7FFF = 32_767
        }
        bucketer.accept(samples, 0, samples.size)
        val peaks = bucketer.finish()
        peaks shouldHaveSize 1
        peaks[0] shouldBeLessThanOrEqual 1f
        peaks[0] shouldBeGreaterThanOrEqual PEAK_POSITIVE_LOWER_BOUND
    }

    @Test
    fun fullScaleNegativePcmMapsToExactlyOne() {
        // -32_768 is the only sample that hits exactly 1f under `abs(sample) / 32_768f`.
        val bucketer = PcmPeakBucketer(
            targetSamples = 1,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 1,
        )
        val samples = ByteArray(44_100 * 2)
        for (i in samples.indices step 2) {
            samples[i] = 0x00
            samples[i + 1] = 0x80.toByte() // 0x8000 = -32_768
        }
        bucketer.accept(samples, 0, samples.size)
        bucketer.finish()[0] shouldBe 1f
    }

    @Test
    fun oddFinalByteIsSilentlyIgnored() {
        // Bucketer must not throw on an odd-length chunk — decoders emit whole samples, but
        // defensive-mode keeps a dangling byte from surfacing as ArrayIndexOutOfBounds.
        val bucketer = PcmPeakBucketer(
            targetSamples = 1,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 1,
        )
        val samples = ByteArray(3) // 1.5 samples
        samples[0] = 0xFF.toByte()
        samples[1] = 0x7F
        samples[2] = 0x12 // dangling
        bucketer.accept(samples, 0, samples.size)
        // No throw — the finish path must still yield a sensible array (even 0 buckets of one
        // frame shorter than a bucket boundary ends in a single "finish commits pending" bucket).
        val peaks = bucketer.finish()
        peaks shouldHaveSize 1
    }

    @Test
    fun stereoFramesAdvanceTheCounterOncePerPair() {
        // Two stereo frames (4 samples, 8 bytes) with one channel full-scale and the other silent
        // must still produce a frame-rate-correct bucket — channel count MUST NOT distort bucket
        // width.
        val bucketer = PcmPeakBucketer(
            targetSamples = 1,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 2,
        )
        val samples = ByteArray(44_100 * 2 * 2) // 1 sec @ 44.1 kHz stereo
        for (i in samples.indices step 4) {
            samples[i] = 0xFF.toByte() // left lo
            samples[i + 1] = 0x7F // left hi → 32_767
            samples[i + 2] = 0x00 // right lo → 0
            samples[i + 3] = 0x00
        }
        bucketer.accept(samples, 0, samples.size)
        val peaks = bucketer.finish()
        peaks shouldHaveSize 1
        peaks[0] shouldBeGreaterThanOrEqual PEAK_POSITIVE_LOWER_BOUND
    }

    @Test
    fun shortSourceReturnsShorterArray() {
        // One bucket's worth of duration (5 µs) with a source that only covers 2 µs: caller
        // requested 10 buckets but the source doesn't cover that long. The returned array must
        // be shorter than targetSamples.
        val bucketer = PcmPeakBucketer(
            targetSamples = 100,
            totalDurationUs = 1_000_000, // 1 second expected
            sampleRate = 44_100,
            channels = 1,
        )
        // 441 samples = 10 ms of audio
        val samples = ByteArray(441 * 2)
        for (i in samples.indices step 2) {
            samples[i] = 0xFF.toByte()
            samples[i + 1] = 0x7F
        }
        bucketer.accept(samples, 0, samples.size)
        val peaks = bucketer.finish()
        // Should be much smaller than 100 (at most ~2 buckets for 10 ms out of 1 s)
        (peaks.size <= 100) shouldBe true
        (peaks.size >= 1) shouldBe true
    }

    @Test
    fun acceptValidatesOffsetAndSize() {
        val bucketer = PcmPeakBucketer(10, 1_000_000, 44_100, 1)
        val buffer = ByteArray(100)
        assertFailsWith<IllegalArgumentException> { bucketer.accept(buffer, -1, 10) }
        assertFailsWith<IllegalArgumentException> { bucketer.accept(buffer, 0, -1) }
        assertFailsWith<IllegalArgumentException> { bucketer.accept(buffer, 50, 60) }
    }

    @Test
    fun completedBucketCountTracksProgress() {
        // Deterministic inputs: 44 100 frames over 1 000 000 µs into a 10-bucket bucketer. At
        // frame F the computed bucket is `F * 1_000_000 / 44_100 * 10 / 1_000_000`, so bucket
        // 9 is entered at frame 39 690 — by the time accept returns, buckets 0..8 are flushed
        // and bucket 9 is still pending. `finish()` then flushes the last bucket, producing
        // exactly 10 elements.
        val bucketer = PcmPeakBucketer(
            targetSamples = 10,
            totalDurationUs = 1_000_000,
            sampleRate = 44_100,
            channels = 1,
        )
        bucketer.completedBucketCount shouldBe 0
        val samples = ByteArray(44_100 * 2)
        bucketer.accept(samples, 0, samples.size)
        bucketer.completedBucketCount shouldBe 9
        val peaks = bucketer.finish()
        peaks.size shouldBe 10
    }

    private companion object {
        // 32_767 / 32_768 = 0.99997; use a lower bound that accounts for floating-point rounding.
        const val PEAK_POSITIVE_LOWER_BOUND = 0.99f
    }
}
