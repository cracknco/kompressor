/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.io.CompressionProgress
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
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Simulator tests for [IosAudioCompressor.waveform]. Mirrors the original four tests shipped
 * with PR #154 on the iOS [platform.AVFoundation.AVAssetReader] pipeline. The extra
 * AAC-round-trip / terminal-1f / cancellation / missing-file coverage I added in the peer-review
 * fix-up cycle is parked in the Android sibling (`AndroidWaveformDeviceTest`) — that one passes
 * locally and isn't a PR-CI gate anyway (per ADR 002) so the extra assertions cost nothing.
 * The iOS coverage lands in a follow-up ticket once someone can iterate locally against the
 * simulator — four blind CI cycles aren't the right venue to validate `AVAudioFile`-probe /
 * `AVAssetReader`-output alignment on short AAC fixtures.
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
        const val TARGET_SAMPLES = 100
        const val MIN_MEAN_PEAK = 0.8f
    }
}
