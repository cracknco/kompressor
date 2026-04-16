/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import kotlinx.cinterop.ExperimentalForeignApi
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.testutil.TestConstants.MONO
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_22K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_48K
import co.crackn.kompressor.testutil.TestConstants.DURATION_TOLERANCE_SEC
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readAudioDurationSec
import co.crackn.kompressor.testutil.readAudioMetadata
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosAudioCompressorTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-audio-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressAudio_producesValidOutput() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "output.m4a"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
    }

    @Test
    fun compressAudio_bitrateAffectsSize() = runTest {
        val inputPath = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputLow = testDir + "low.m4a"
        val outputHigh = testDir + "high.m4a"

        // 64kbps and 192kbps both sit inside the supported AAC stereo/44.1kHz range.
        // Earlier versions of this test used 32kbps which AVAssetWriter silently clamps
        // to its per-channel minimum on stereo — making both outputs identical size.
        val lowResult = compressor.compress(inputPath, outputLow, AudioCompressionConfig(bitrate = 64_000))
        val highResult = compressor.compress(inputPath, outputHigh, AudioCompressionConfig(bitrate = 192_000))
        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)

        val sizeLow = fileSize(outputLow)
        val sizeHigh = fileSize(outputHigh)
        assertTrue(sizeLow < sizeHigh, "64kbps ($sizeLow) should be < 192kbps ($sizeHigh)")
    }

    @Test
    fun compressAudio_monoChannelCountIsHonoured() = runTest {
        // AAC at a fixed bitrate produces roughly the same file size regardless of channel count,
        // so comparing mono vs stereo byte sizes at equal bitrate is not a reliable signal.
        // Instead verify the functional contract: the output carries the configured channel
        // count, which is what callers actually observe.
        val inputPath = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputMono = testDir + "mono.m4a"
        val outputStereo = testDir + "stereo.m4a"
        val config = AudioCompressionConfig(bitrate = 64_000)

        val monoResult = compressor.compress(inputPath, outputMono, config.copy(channels = AudioChannels.MONO))
        val stereoResult = compressor.compress(inputPath, outputStereo, config.copy(channels = AudioChannels.STEREO))
        assertTrue(monoResult.isSuccess, "mono compression failed: ${monoResult.exceptionOrNull()}")
        assertTrue(stereoResult.isSuccess, "stereo compression failed: ${stereoResult.exceptionOrNull()}")

        assertEquals(MONO, readAudioMetadata(outputMono).channels, "Output should be mono when configured")
        assertEquals(STEREO, readAudioMetadata(outputStereo).channels, "Output should be stereo when configured")
    }

    @Test
    fun compressAudio_monoToStereo_rejectsWithTypedError() = runTest {
        // iOS's AVAssetReader/AVAssetWriter pipeline doesn't upmix mono → stereo. Rather than
        // silently producing a mono output that violates the caller's config, the compressor
        // rejects the combination with a typed [AudioCompressionError.UnsupportedConfiguration]
        // so callers can `when`-branch on it (e.g. fall back to requesting mono output).
        val inputPath = createTestWavFile(1, SAMPLE_RATE_44K, MONO)
        val outputPath = testDir + "mono_to_stereo.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(channels = AudioChannels.STEREO),
        )

        assertTrue(result.isFailure, "Mono→stereo must fail fast with a typed error")
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.UnsupportedConfiguration,
            "Expected UnsupportedConfiguration, got ${err?.let { it::class.simpleName }}: ${err?.message}",
        )
    }

    @Test
    fun cancellation_deletesPartialOutput() = kotlinx.coroutines.runBlocking {
        // iOS mirror of the Android cancellation test. Long input ensures AVAssetReader /
        // AVAssetWriter are mid-copy when cancel lands — `IosPipeline.copySamples` checks
        // `currentCoroutineContext().ensureActive()` on each buffer, so the first yield after
        // cancel throws `CancellationException`. The new `deletingOutputOnFailure` wrapper
        // (iosMain) must then remove the partial .m4a before the coroutine unwinds.
        val inputPath = createTestWavFile(30, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "cancelled.m4a"
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job(),
        )
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            compressor.compress(
                inputPath = inputPath,
                outputPath = outputPath,
                config = AudioCompressionConfig(sampleRate = 22_050),
                onProgress = { p -> if (p > 0f && !started.isCompleted) started.complete(Unit) },
            )
        }
        kotlinx.coroutines.withTimeout(5_000L) { started.await() }
        job.cancel()
        kotlinx.coroutines.withTimeout(15_000L) { job.join() }

        assertTrue(job.isCancelled, "Job must be cancelled to validate partial-output cleanup")
        assertTrue(
            !NSFileManager.defaultManager.fileExistsAtPath(outputPath),
            "Cancelled iOS audio export must delete its partial output",
        )
    }

    @Test
    fun compressAudio_fileNotFound_returnsFailure() = runTest {
        val result = compressor.compress("/nonexistent/audio.wav", testDir + "out.m4a")
        assertTrue(result.isFailure)
    }

    @Test
    fun compressAudio_progressReported() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "progress.m4a"
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first())
        assertEquals(1f, progressValues.last())
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun compressAudio_48kTo44k_producesValidOutput() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_48K, STEREO)
        val outputPath = testDir + "48k_to_44k.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_44K),
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that resampling actually occurred
        val metadata = readAudioMetadata(outputPath)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should be resampled to 44.1kHz")
        assertEquals(STEREO, metadata.channels, "Output should maintain stereo channels")
    }

    @Test
    fun compressAudio_44kTo22k_voiceMessage() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "voice_message.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioPresets.VOICE_MESSAGE,
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that resampling and channel conversion occurred for voice preset
        val metadata = readAudioMetadata(outputPath)
        assertEquals(SAMPLE_RATE_22K, metadata.sampleRate, "Voice message should be resampled to 22.05kHz")
        assertEquals(MONO, metadata.channels, "Voice message should be converted to mono")
    }

    @Test
    fun compressAudio_stereoToMono_sameSampleRate() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "stereo_to_mono.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(channels = AudioChannels.MONO),
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that channel conversion actually occurred
        val metadata = readAudioMetadata(outputPath)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should maintain 44.1kHz sample rate")
        assertEquals(MONO, metadata.channels, "Output should be converted to mono")
    }

    // NOTE: mono → stereo upmix is not supported by the current iOS pipeline. AVAssetReader-
    // TrackOutput with AVNumberOfChannelsKey = 2 on a mono source does NOT automatically
    // duplicate the channel; the decoded PCM remains 1-channel and the output stays mono.
    // Supporting this would require explicit PCM buffer fan-out in IosPipeline. Stereo → mono
    // (downmix) is covered by `compressAudio_stereoToMono_sameSampleRate` above and works via
    // AVFoundation's built-in averaging.

    @Test
    fun compressAudio_sampleRateConversion_preservesDuration() = runTest {
        val durationSec = 2
        val inputPath = createTestWavFile(durationSec, SAMPLE_RATE_48K, STEREO)
        val outputPath = testDir + "duration_check.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_22K),
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")

        val outputDurationSec = readAudioDurationSec(outputPath)
        assertTrue(
            kotlin.math.abs(outputDurationSec - durationSec.toDouble()) < DURATION_TOLERANCE_SEC,
            "Output duration ${outputDurationSec}s should be within " +
                "${DURATION_TOLERANCE_SEC}s of ${durationSec}s",
        )

        // Assert that resampling actually occurred
        val metadata = readAudioMetadata(outputPath)
        assertEquals(SAMPLE_RATE_22K, metadata.sampleRate, "Output should be resampled to 22.05kHz")
        assertEquals(STEREO, metadata.channels, "Output should maintain stereo channels")
    }

    @Suppress("SameParameterValue")
    private fun createTestWavFile(durationSeconds: Int, sampleRate: Int, channels: Int): String {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val path = testDir + "test_${sampleRate}hz_${channels}ch_${durationSeconds}s.wav"
        writeBytes(path, bytes)
        return path
    }

}