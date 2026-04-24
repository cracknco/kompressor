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
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.test.fail
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
        val peaks = result.successOrFail()
        peaks.size shouldBe TARGET_SAMPLES
        peaks.forEach { peak ->
            peak shouldBeGreaterThanOrEqual 0f
            peak shouldBeLessThanOrEqual 1f
        }
        // 440 Hz at full amplitude must produce peaks near 1.0 in every bucket.
        val meanPeak = peaks.average().toFloat()
        meanPeak shouldBeGreaterThanOrEqual MIN_MEAN_PEAK
    }

    @Test
    fun waveform_onZeroTargetSamplesRejectsAsIllegalArgument() = runTest {
        val input = File(tempDir, "short.wav").apply {
            writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE, 1))
        }
        val result = compressor.waveform(MediaSource.Local.FilePath(input.absolutePath), targetSamples = 0)
        val err = result.failureOrFail()
        err.shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun waveform_onImageInputFailsWithNoAudioTrack() = runTest {
        // Minimal non-audio file — a valid 4-byte JPEG marker won't parse as audio container.
        val input = File(tempDir, "stub.bin").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val result = compressor.waveform(MediaSource.Local.FilePath(input.absolutePath))
        val err = result.failureOrFail()
        withClue("expected typed AudioCompressionError, got $err") {
            (
                err is AudioCompressionError.NoAudioTrack ||
                    err is AudioCompressionError.IoFailed ||
                    err is AudioCompressionError.SourceNotFound ||
                    err is AudioCompressionError.UnsupportedSourceFormat
                ) shouldBe true
        }
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
            progress.fraction shouldBeGreaterThanOrEqual 0f
            progress.fraction shouldBeLessThanOrEqual 1f
        }
        result.successOrFail()
        (CompressionProgress.Phase.COMPRESSING in phases) shouldBe true
        (CompressionProgress.Phase.FINALIZING_OUTPUT in phases) shouldBe false
    }

    @Test
    fun waveform_on440HzToneEncodedToAac_producesNearFullScalePeaks() = runTest {
        // Exercises the real MediaCodec *decode* path — WAV input flows through unchanged, but
        // an AAC-in-M4A round-trip forces the extractor to actually instantiate an AAC decoder
        // and drain PCM out. Regression cover for "Media3 upgrade changes the PCM output
        // layout for one codec".
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
        compressResult.successOrFail()

        val result = compressor.waveform(
            MediaSource.Local.FilePath(aac.absolutePath),
            targetSamples = TARGET_SAMPLES,
        )
        val peaks = result.successOrFail()
        peaks.size shouldBe TARGET_SAMPLES
        peaks.forEach { peak ->
            peak shouldBeGreaterThanOrEqual 0f
            peak shouldBeLessThanOrEqual 1f
        }
        // Lossy AAC shaves peaks slightly — relax the bound vs. the WAV case.
        val meanPeak = peaks.average().toFloat()
        meanPeak shouldBeGreaterThanOrEqual MIN_AAC_MEAN_PEAK
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
        result.successOrFail()
        lastFraction shouldBe 1f
    }

    @Test
    fun waveform_cancellationPropagatesAndReleasesResources() = runBlocking {
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
        // Mirror AndroidVideoCompressorTest.cancellation_deletesPartialOutput: `launch` + wait
        // for first progress via a `CompletableDeferred<Unit>` to guarantee the pump is inside
        // the MediaCodec loop before we cancel. Avoids a race where `cancel` fires before
        // `extractAndroidWaveform` enters its `ensureActive()`-guarded loop.
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
        job.isCancelled shouldBe true

        // Re-running waveform on the same compressor must still succeed — proves no leaked
        // codec or extractor from the cancelled call is blocking a subsequent one.
        val second = compressor.waveform(
            MediaSource.Local.FilePath(input.absolutePath),
            targetSamples = TARGET_SAMPLES,
        )
        second.successOrFail()
    }

    @Test
    fun waveform_onMissingFileReturnsSourceNotFound() = runTest {
        val missing = File(tempDir, "does-not-exist.wav")
        val result = compressor.waveform(MediaSource.Local.FilePath(missing.absolutePath))
        val err = result.failureOrFail()
        withClue("expected SourceNotFound or IoFailed for missing file, got $err") {
            (
                err is AudioCompressionError.SourceNotFound ||
                    err is AudioCompressionError.IoFailed
                ) shouldBe true
        }
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


/**
 * Local helpers that avoid `io.kotest.matchers.result.shouldBeSuccess` / `shouldBeFailure`
 * because those matchers aren't consistently available across Kotlin/Native targets in the
 * kotest 6.1.11 artefact bundle. Asserting on `Result` via `getOrThrow` / `exceptionOrNull`
 * plus a Kotest-authored `fail(...)` keeps failure messages rich without the K/N gap.
 */
private fun <T> Result<T>.successOrFail(): T = getOrElse { fail("expected success, got: $it") }

private fun Result<*>.failureOrFail(): Throwable =
    exceptionOrNull() ?: fail("expected failure, got success: ${getOrNull()}")
