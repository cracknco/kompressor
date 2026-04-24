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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
        val inputPath = writeTestWav(1, MONO)
        val result = compressor.waveform(MediaSource.Local.FilePath(inputPath), targetSamples = 0)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "expected IllegalArgumentException, got $err")
    }

    @Test
    fun waveform_onNonAudioInputFailsWithTypedError() = runTest {
        val path = testDir + "stub.bin"
        writeBytes(path, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val result = compressor.waveform(MediaSource.Local.FilePath(path))
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
        val inputPath = writeTestWav(DURATION_SEC, MONO)
        val phases = mutableSetOf<CompressionProgress.Phase>()
        val result = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
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
        assertTrue(
            encodeResult.isSuccess,
            "pre-flight AAC encode failed: ${encodeResult.exceptionOrNull()}",
        )

        val result = compressor.waveform(
            MediaSource.Local.FilePath(aacPath),
            targetSamples = TARGET_SAMPLES,
        )
        assertTrue(result.isSuccess, "waveform on AAC failed: ${result.exceptionOrNull()}")
        val peaks = result.getOrThrow()
        assertEquals(TARGET_SAMPLES, peaks.size, "peak count should match target")
        peaks.forEach { peak -> assertTrue(peak in 0f..1f, "peak out of range: $peak") }
        val meanPeak = peaks.average().toFloat()
        assertTrue(meanPeak > MIN_AAC_MEAN_PEAK, "AAC mean peak should exceed $MIN_AAC_MEAN_PEAK, was $meanPeak")
    }

    @Test
    fun waveform_terminalProgressTickReaches1f() = runTest {
        val inputPath = writeTestWav(DURATION_SEC, MONO)
        var lastFraction = -1f
        val result = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
            targetSamples = TARGET_SAMPLES,
        ) { progress -> lastFraction = progress.fraction }
        assertTrue(result.isSuccess)
        assertEquals(1f, lastFraction, "waveform must end with COMPRESSING(1f)")
    }

    @Test
    fun waveform_cancellationPropagates() = runBlocking {
        val inputPath = writeTestWav(LONG_DURATION_SEC, MONO)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val deferred = scope.async {
            compressor.waveform(
                MediaSource.Local.FilePath(inputPath),
                targetSamples = 10_000,
            )
        }
        delay(100L)
        deferred.cancel()
        val err = runCatching { withTimeout(15_000L) { deferred.await() } }.exceptionOrNull()
        assertTrue(err is CancellationException, "expected CancellationException, got $err")

        // Subsequent call on the same compressor must still succeed — proves no leaked AVAssetReader.
        val second = compressor.waveform(
            MediaSource.Local.FilePath(inputPath),
            targetSamples = TARGET_SAMPLES,
        )
        assertTrue(second.isSuccess, "post-cancel waveform failed: ${second.exceptionOrNull()}")
    }

    @Test
    fun waveform_onMissingFileReturnsSourceNotFound() = runTest {
        val missingPath = testDir + "does-not-exist.wav"
        val result = compressor.waveform(MediaSource.Local.FilePath(missingPath))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.SourceNotFound ||
                err is AudioCompressionError.IoFailed,
            "expected SourceNotFound or IoFailed for missing file, got $err",
        )
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
