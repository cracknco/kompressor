/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.WavGenerator
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        val meanPeak = peaks.average().toFloat()
        assertTrue(meanPeak > MIN_MEAN_PEAK, "mean peak should exceed $MIN_MEAN_PEAK, was $meanPeak")
    }

    @Test
    fun waveform_onZeroTargetSamplesRejectsAsIllegalArgument() = runTest {
        val input = File(tempDir, "short.wav").apply {
            writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            compressor.waveform(
                MediaSource.Local.FilePath(input.absolutePath),
                targetSamples = 0,
            ).getOrThrow()
        }
    }

    @Test
    fun waveform_onImageInputFailsWithNoAudioTrack() = runTest {
        val input = File(tempDir, "stub.bin").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val result = compressor.waveform(MediaSource.Local.FilePath(input.absolutePath))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.NoAudioTrack ||
                err is AudioCompressionError.IoFailed ||
                err is AudioCompressionError.SourceNotFound ||
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
        val phases = mutableSetOf<CompressionProgress.Phase>()
        val result = compressor.waveform(
            MediaSource.Local.FilePath(input.absolutePath),
            targetSamples = TARGET_SAMPLES,
        ) { progress ->
            phases += progress.phase
            assertTrue(progress.fraction in 0f..1f)
        }
        assertTrue(result.isSuccess)
        assertTrue(CompressionProgress.Phase.COMPRESSING in phases)
        assertFalse(CompressionProgress.Phase.FINALIZING_OUTPUT in phases)
    }

    @Test
    fun waveform_on440HzToneEncodedToAac_producesNearFullScalePeaks() = runTest {
        // Exercises the real MediaCodec *decode* path — WAV input flows through unchanged, but
        // an AAC-in-M4A round-trip forces the extractor to actually instantiate an AAC decoder
        // and drain PCM out.
        val wav = File(tempDir, "source.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(
                    durationSeconds = DURATION_SEC,
                    sampleRate = SAMPLE_RATE,
                    channels = 1,
                    perChannelFrequencyMultiplier = { 1.0 },
                ),
            )
        }
        val aac = File(tempDir, "source.m4a")
        val compressResult = compressor.compress(
            MediaSource.Local.FilePath(wav.absolutePath),
            MediaDestination.Local.FilePath(aac.absolutePath),
            AudioCompressionConfig(channels = AudioChannels.MONO),
        )
        assertTrue(compressResult.isSuccess, "pre-flight AAC encode failed: ${compressResult.exceptionOrNull()}")

        val result = compressor.waveform(
            MediaSource.Local.FilePath(aac.absolutePath),
            targetSamples = TARGET_SAMPLES,
        )
        assertTrue(result.isSuccess, "waveform on AAC failed: ${result.exceptionOrNull()}")
        val peaks = result.getOrThrow()
        assertEquals(TARGET_SAMPLES, peaks.size, "peak count should match target for a long source")
        peaks.forEach { peak ->
            assertTrue(peak in 0f..1f, "peak out of range: $peak")
        }
        val meanPeak = peaks.average().toFloat()
        assertTrue(meanPeak > MIN_AAC_MEAN_PEAK, "AAC mean peak should exceed $MIN_AAC_MEAN_PEAK, was $meanPeak")
    }

    @Test
    fun waveform_terminalProgressTickReaches1f() = runTest {
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
        var lastFraction = -1f
        val result = compressor.waveform(
            MediaSource.Local.FilePath(input.absolutePath),
            targetSamples = TARGET_SAMPLES,
        ) { progress -> lastFraction = progress.fraction }
        assertTrue(result.isSuccess)
        assertEquals(1f, lastFraction, "waveform must end with COMPRESSING(1f)")
    }

    @Test
    fun waveform_cancellationPropagatesAndReleasesResources() = runBlocking {
        // Mirror AndroidVideoCompressorTest.cancellation_deletesPartialOutput: `launch` + wait
        // for first progress via a `CompletableDeferred<Unit>` to guarantee the pump is inside
        // the MediaCodec loop before we cancel.
        val input = File(tempDir, "long.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(
                    durationSeconds = LONG_DURATION_SEC,
                    sampleRate = SAMPLE_RATE,
                    channels = 1,
                    perChannelFrequencyMultiplier = { 1.0 },
                ),
            )
        }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val started = CompletableDeferred<Unit>()
        val job = scope.launch {
            compressor.waveform(
                MediaSource.Local.FilePath(input.absolutePath),
                targetSamples = 10_000,
            ) { p ->
                if (p.fraction > 0f && !started.isCompleted) started.complete(Unit)
            }
        }
        withTimeout(10_000L) { started.await() }
        job.cancel()
        withTimeout(15_000L) { job.join() }
        assertTrue(job.isCancelled, "Job must be cancelled to exercise the pump's cancel path")

        val second = compressor.waveform(
            MediaSource.Local.FilePath(input.absolutePath),
            targetSamples = TARGET_SAMPLES,
        )
        assertTrue(second.isSuccess, "post-cancel waveform failed: ${second.exceptionOrNull()}")
    }

    @Test
    fun waveform_onMissingFileReturnsSourceNotFound() = runTest {
        val missing = File(tempDir, "does-not-exist.wav")
        val result = compressor.waveform(MediaSource.Local.FilePath(missing.absolutePath))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.SourceNotFound ||
                err is AudioCompressionError.IoFailed,
            "expected SourceNotFound or IoFailed for missing file, got $err",
        )
    }

    private companion object {
        const val DURATION_SEC = 2
        const val LONG_DURATION_SEC = 20
        const val SAMPLE_RATE = 44_100
        const val TARGET_SAMPLES = 100
        const val MIN_MEAN_PEAK = 0.8f
        const val MIN_AAC_MEAN_PEAK = 0.6f
    }
}
