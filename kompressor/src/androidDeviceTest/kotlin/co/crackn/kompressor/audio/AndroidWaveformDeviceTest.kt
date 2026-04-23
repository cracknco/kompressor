/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.WavGenerator
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Device-only tests for [AndroidAudioCompressor.waveform]. Exercises the real
 * [android.media.MediaCodec] pipeline: these can't run on the host JVM because MediaCodec lives
 * in the framework.
 *
 * The fixture input is always a freshly generated WAV — we drop straight into the PCM pump
 * without any intermediate encode step, so a failure here is always a pump / bucketer issue and
 * not a codec mismatch.
 */
class AndroidWaveformDeviceTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-waveform-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun waveform_on440HzToneProducesNearFullScalePeaks() = runTest {
        val input = File(tempDir, "sine.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(
                    durationSeconds = DURATION_SEC,
                    sampleRate = SAMPLE_RATE,
                    channels = 1,
                    perChannelFrequencyMultiplier = { 1.0 },
                ),
            )
        }
        val result = compressor.waveform(
            MediaSource.Local.FilePath(input.absolutePath),
            targetSamples = TARGET_SAMPLES,
        )
        assertTrue(result.isSuccess, "waveform failed: ${result.exceptionOrNull()}")
        val peaks = result.getOrThrow()
        assertEquals(TARGET_SAMPLES, peaks.size, "peak count should match target for a long source")
        peaks.forEach { peak ->
            assertTrue(peak in 0f..1f, "peak out of range: $peak")
        }
        // 440 Hz at full amplitude must produce peaks near 1.0 in every bucket.
        val meanPeak = peaks.average().toFloat()
        assertTrue(meanPeak > MIN_MEAN_PEAK, "mean peak should exceed $MIN_MEAN_PEAK, was $meanPeak")
    }

    @Test
    fun waveform_onZeroTargetSamplesRejectsAsIllegalArgument() = runTest {
        val input = File(tempDir, "short.wav").apply {
            writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE, 1))
        }
        val result = compressor.waveform(MediaSource.Local.FilePath(input.absolutePath), targetSamples = 0)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "expected IllegalArgumentException, got $err")
    }

    @Test
    fun waveform_onImageInputFailsWithNoAudioTrack() = runTest {
        // Minimal non-audio file — a valid 4-byte JPEG marker won't parse as audio container.
        val input = File(tempDir, "stub.bin").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val result = compressor.waveform(MediaSource.Local.FilePath(input.absolutePath))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.NoAudioTrack ||
                err is AudioCompressionError.IoFailed ||
                err is AudioCompressionError.UnsupportedSourceFormat,
            "expected typed AudioCompressionError, got $err",
        )
    }

    @Test
    fun waveform_reportsCompressingProgressOnly() = runTest {
        val input = File(tempDir, "ticks.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(DURATION_SEC, SAMPLE_RATE, 1, perChannelFrequencyMultiplier = { 1.0 }),
            )
        }
        val phases = mutableSetOf<co.crackn.kompressor.io.CompressionProgress.Phase>()
        val result = compressor.waveform(
            MediaSource.Local.FilePath(input.absolutePath),
            targetSamples = TARGET_SAMPLES,
        ) { progress ->
            phases += progress.phase
            assertTrue(
                progress.fraction in 0f..1f,
                "fraction must be [0,1], was ${progress.fraction}",
            )
        }
        assertTrue(result.isSuccess)
        assertTrue(
            co.crackn.kompressor.io.CompressionProgress.Phase.COMPRESSING in phases,
            "expected at least one COMPRESSING tick",
        )
        assertFalse(
            co.crackn.kompressor.io.CompressionProgress.Phase.FINALIZING_OUTPUT in phases,
            "waveform must not emit FINALIZING_OUTPUT",
        )
    }

    private companion object {
        const val DURATION_SEC = 2
        const val SAMPLE_RATE = 44_100
        const val TARGET_SAMPLES = 100
        const val MIN_MEAN_PEAK = 0.8f
    }
}
