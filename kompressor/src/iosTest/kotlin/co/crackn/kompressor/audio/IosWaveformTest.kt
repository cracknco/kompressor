/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.TestConstants.MONO
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.writeBytes
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Simulator tests for [IosAudioCompressor.waveform]. Mirrors
 * [co.crackn.kompressor.audio.AndroidWaveformDeviceTest] — asserting the same waveform
 * contract on the iOS [platform.AVFoundation.AVAssetReader] pipeline.
 */
class IosWaveformTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-waveform-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun waveform_on440HzMonoWavProducesNearFullScalePeaks() = runTest {
        val inputPath = writeTestWav(DURATION_SEC, MONO)
        val result = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
            targetSamples = TARGET_SAMPLES,
        )
        val peaks = result.successOrFail()
        peaks.size shouldBe TARGET_SAMPLES
        peaks.forEach { peak ->
            peak shouldBeGreaterThanOrEqual 0f
            peak shouldBeLessThanOrEqual 1f
        }
        val meanPeak = peaks.average().toFloat()
        meanPeak shouldBeGreaterThanOrEqual MIN_MEAN_PEAK
    }

    @Test
    fun waveform_onZeroTargetSamplesRejectsAsIllegalArgument() = runTest {
        val inputPath = writeTestWav(1, MONO)
        val result = compressor.waveform(MediaSource.Local.FilePath(inputPath), targetSamples = 0)
        val err = result.failureOrFail()
        err.shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun waveform_onNonAudioInputFailsWithTypedError() = runTest {
        val path = testDir + "stub.bin"
        writeBytes(path, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val result = compressor.waveform(MediaSource.Local.FilePath(path))
        val err = result.failureOrFail()
        withClue("expected typed AudioCompressionError, got $err") {
            (
                err is AudioCompressionError.NoAudioTrack ||
                    err is AudioCompressionError.IoFailed ||
                    err is AudioCompressionError.UnsupportedSourceFormat
                ) shouldBe true
        }
    }

    @Test
    fun waveform_reportsCompressingProgressOnly() = runTest {
        val inputPath = writeTestWav(DURATION_SEC, MONO)
        val phases = mutableSetOf<CompressionProgress.Phase>()
        val result = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
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
    fun waveform_on440HzMonoEncodedToAac_producesNearFullScalePeaks() = runTest {
        // Decoder-path coverage: WAV flows through AVAssetReader as 16-bit PCM without any
        // codec involved, but an AAC-in-M4A round-trip forces AVAssetReader to decode.
        val wavPath = writeTestWav(DURATION_SEC, MONO)
        val aacPath = testDir + "source.m4a"
        val encodeResult = compressor.compress(
            MediaSource.Local.FilePath(wavPath),
            MediaDestination.Local.FilePath(aacPath),
            AudioCompressionConfig(channels = AudioChannels.MONO),
        )
        encodeResult.successOrFail()

        val result = compressor.waveform(
            MediaSource.Local.FilePath(aacPath),
            targetSamples = TARGET_SAMPLES,
        )
        val peaks = result.successOrFail()
        peaks.size shouldBe TARGET_SAMPLES
        peaks.forEach { peak ->
            peak shouldBeGreaterThanOrEqual 0f
            peak shouldBeLessThanOrEqual 1f
        }
        val meanPeak = peaks.average().toFloat()
        meanPeak shouldBeGreaterThanOrEqual MIN_AAC_MEAN_PEAK
    }

    @Test
    fun waveform_terminalProgressTickReaches1f() = runTest {
        val inputPath = writeTestWav(DURATION_SEC, MONO)
        var lastFraction = -1f
        val result = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
            targetSamples = TARGET_SAMPLES,
        ) { progress -> lastFraction = progress.fraction }
        result.successOrFail()
        lastFraction shouldBe 1f
    }

    @Test
    fun waveform_cancellationPropagates() = runBlocking {
        // Mirror IosAudioCompressorTest.cancellation_deletesPartialOutput: `launch` + wait for
        // first progress via a `CompletableDeferred<Unit>` to guarantee the pump is inside the
        // AVAssetReader loop before we cancel. This avoids a race where `cancel` fires before
        // `extractIosWaveform` enters its `ensureActive()`-guarded loop, which would report
        // the cancellation via await-rethrow but never actually exercise the pump's cancel path.
        val inputPath = writeTestWav(LONG_DURATION_SEC, MONO)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val started = CompletableDeferred<Unit>()
        val job = scope.launch {
            compressor.waveform(
                MediaSource.Local.FilePath(inputPath),
                targetSamples = 10_000,
            ) { p ->
                if (p.fraction > 0f && !started.isCompleted) started.complete(Unit)
            }
        }
        withTimeout(10_000L) { started.await() }
        job.cancel()
        withTimeout(15_000L) { job.join() }
        job.isCancelled shouldBe true

        // Subsequent call on the same compressor must still succeed — proves no leaked AVAssetReader.
        val second = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
            targetSamples = TARGET_SAMPLES,
        )
        second.successOrFail()
    }

    @Test
    fun waveform_onMissingFileReturnsSourceNotFound() = runTest {
        val missingPath = testDir + "does-not-exist.wav"
        val result = compressor.waveform(MediaSource.Local.FilePath(missingPath))
        val err = result.failureOrFail()
        withClue("expected SourceNotFound or IoFailed for missing file, got $err") {
            (
                err is AudioCompressionError.SourceNotFound ||
                    err is AudioCompressionError.IoFailed
                ) shouldBe true
        }
    }

    private fun writeTestWav(durationSec: Int, channels: Int): String {
        val bytes = WavGenerator.generateWavBytes(
            durationSeconds = durationSec,
            sampleRate = SAMPLE_RATE_44K,
            channels = channels,
            perChannelFrequencyMultiplier = { 1.0 },
        )
        val path = testDir + "waveform_${channels}ch_${durationSec}s.wav"
        writeBytes(path, bytes)
        return path
    }

    private companion object {
        const val DURATION_SEC = 2
        const val LONG_DURATION_SEC = 20
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
