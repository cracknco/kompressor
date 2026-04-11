@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.golden

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.DURATION_TOLERANCE_SEC
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readAudioDurationSec
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for iOS audio compression. Verify that compression of known inputs
 * produces outputs within expected functional criteria.
 */
class GoldenAudioTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-golden-audio-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun stereo44k_defaultConfig_producesExpectedOutput() = runTest {
        val inputPath = createWav(durationSeconds = 2, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val outputPath = testDir + "golden_default.m4a"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0, "Output must be non-empty")
        assertTrue(
            compression.outputSize < compression.inputSize,
            "AAC should compress PCM WAV",
        )

        // Duration preserved
        val outputDurationSec = readAudioDurationSec(outputPath)
        assertTrue(
            kotlin.math.abs(outputDurationSec - EXPECTED_DURATION_2S) < DURATION_TOLERANCE_SEC,
            "Duration ${outputDurationSec}s should be ~${EXPECTED_DURATION_2S}s",
        )
    }

    @Test
    fun stereo44k_voicePreset_producesSmaller() = runTest {
        val inputPath = createWav(durationSeconds = 2, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val voiceOutput = testDir + "golden_voice.m4a"
        val defaultOutput = testDir + "golden_default_compare.m4a"

        compressor.compress(inputPath, voiceOutput, AudioPresets.VOICE_MESSAGE)
        compressor.compress(inputPath, defaultOutput)

        val voiceSize = fileSize(voiceOutput)
        val defaultSize = fileSize(defaultOutput)
        assertTrue(voiceSize > 0, "Voice output should be non-empty")
        assertTrue(
            voiceSize < defaultSize,
            "Voice preset ($voiceSize) should be smaller than default ($defaultSize)",
        )
    }

    @Test
    fun outputSizeWithinExpectedRange() = runTest {
        val inputPath = createWav(durationSeconds = 3, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val outputPath = testDir + "golden_size_check.m4a"

        val result = compressor.compress(
            inputPath, outputPath,
            AudioCompressionConfig(bitrate = 128_000),
        )

        assertTrue(result.isSuccess)
        val size = fileSize(outputPath)
        // 3s at 128kbps ≈ 48KB, allow 50% margin → 24KB-72KB
        assertTrue(
            size in EXPECTED_MIN_BYTES..EXPECTED_MAX_BYTES,
            "3s at 128kbps: expected $EXPECTED_MIN_BYTES-$EXPECTED_MAX_BYTES bytes, got $size",
        )
    }

    private fun createWav(durationSeconds: Int, sampleRate: Int, channels: Int): String {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val path = testDir + "golden_input_${sampleRate}_${channels}ch_${durationSeconds}s.wav"
        writeBytes(path, bytes)
        return path
    }

    private companion object {
        const val EXPECTED_DURATION_2S = 2.0
        const val EXPECTED_MIN_BYTES = 24_000L
        const val EXPECTED_MAX_BYTES = 72_000L
    }
}
