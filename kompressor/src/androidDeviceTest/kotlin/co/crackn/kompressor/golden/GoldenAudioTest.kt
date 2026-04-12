package co.crackn.kompressor.golden

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.TestConstants.DURATION_TOLERANCE_MS
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readAudioDurationMs
import co.crackn.kompressor.testutil.readAudioMetadata
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Golden tests verify that compression of known inputs produces outputs within
 * expected functional criteria. These catch regressions in encoder behavior,
 * file size expectations, and metadata correctness.
 */
class GoldenAudioTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-golden-audio").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun stereo44k_defaultConfig_producesExpectedOutput() = runTest {
        val input = createWav(durationSeconds = 2, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_default.m4a")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()

        assertTrue(output.exists(), "Output file must exist")
        assertTrue(compression.outputSize > 0, "Output must be non-empty")
        assertTrue(
            compression.outputSize < compression.inputSize,
            "AAC should compress PCM WAV: output=${compression.outputSize}, input=${compression.inputSize}",
        )
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        val outputDurationMs = readAudioDurationMs(output)
        assertTrue(
            abs(outputDurationMs - EXPECTED_DURATION_2S_MS) < DURATION_TOLERANCE_MS,
            "Duration $outputDurationMs ms should be within ${DURATION_TOLERANCE_MS}ms of ${EXPECTED_DURATION_2S_MS}ms",
        )

        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Sample rate should match default config")
        assertEquals(STEREO, metadata.channels, "Channel count should match default config")
    }

    @Test
    fun stereo44k_voicePreset_producesExpectedOutput() = runTest {
        val input = createWav(durationSeconds = 2, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_voice.m4a")

        val result = compressor.compress(input.absolutePath, output.absolutePath, AudioPresets.VOICE_MESSAGE)

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        val metadata = readAudioMetadata(output)
        assertEquals(AudioPresets.VOICE_MESSAGE.sampleRate, metadata.sampleRate, "Voice preset: 22.05kHz")
        assertEquals(AudioChannels.MONO.count, metadata.channels, "Voice preset: mono")

        // Voice preset at 32kbps should be significantly smaller than default 128kbps
        val defaultOutput = File(tempDir, "golden_default_compare.m4a")
        compressor.compress(input.absolutePath, defaultOutput.absolutePath)
        assertTrue(
            output.length() < defaultOutput.length(),
            "Voice preset should produce smaller file than default",
        )
    }

    @Test
    fun outputSizeWithinExpectedRange() = runTest {
        val input = createWav(durationSeconds = 3, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_size_check.m4a")

        val result = compressor.compress(
            input.absolutePath,
            output.absolutePath,
            AudioCompressionConfig(bitrate = 128_000),
        )

        assertTrue(result.isSuccess)
        assertTrue(
            output.length() in EXPECTED_MIN_BYTES..EXPECTED_MAX_BYTES,
            "3s at 128kbps: expected $EXPECTED_MIN_BYTES-$EXPECTED_MAX_BYTES bytes, got ${output.length()}",
        )
    }

    @Test
    fun aacInput_matchingConfig_triggersFastPath() = runTest {
        val input = File(tempDir, "golden_aac_in.m4a")
        AudioInputFixtures.createAacM4a(
            input,
            durationSeconds = 2,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
            bitrate = 128_000,
        )
        val output = File(tempDir, "golden_aac_passthrough.m4a")

        val start = System.nanoTime()
        val result = compressor.compress(input.absolutePath, output.absolutePath)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        assertTrue(result.isSuccess, "Passthrough should succeed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidM4a(output.readBytes()))
        // Passthrough should be very fast (<3s even on slow emulators). A full transcode of
        // 2s of audio typically takes >3s — this gap pins the fast path.
        assertTrue(
            elapsedMs < FAST_PATH_BUDGET_MS,
            "Passthrough took ${elapsedMs}ms, expected < ${FAST_PATH_BUDGET_MS}ms",
        )
        // Output size should be within a tight tolerance of input (remux only, no re-encode).
        val ratio = output.length().toDouble() / input.length().toDouble()
        assertTrue(
            ratio in FAST_PATH_SIZE_RATIO_MIN..FAST_PATH_SIZE_RATIO_MAX,
            "Passthrough size ratio $ratio should be near 1.0",
        )
    }

    @Test
    fun outputBitrateLowerThanInputForVoicePreset() = runTest {
        val input = createWav(durationSeconds = 3, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_voice_vs_input.m4a")

        val result = compressor.compress(input.absolutePath, output.absolutePath, AudioPresets.VOICE_MESSAGE)
        assertTrue(result.isSuccess)

        // Voice preset is 32kbps mono 22kHz — vastly smaller than 44.1kHz stereo PCM input.
        assertTrue(
            output.length() < input.length() / 4,
            "Voice preset output ${output.length()} should be <1/4 of PCM input ${input.length()}",
        )
    }

    @Test
    fun outputDurationMatchesInputAcrossRateConversion() = runTest {
        val durationSec = 3
        val input = createWav(durationSeconds = durationSec, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_duration_preservation.m4a")

        val result = compressor.compress(
            input.absolutePath,
            output.absolutePath,
            AudioCompressionConfig(sampleRate = 22_050, channels = AudioChannels.MONO),
        )
        assertTrue(result.isSuccess)

        val outMs = readAudioDurationMs(output)
        val expectedMs = durationSec * 1_000L
        assertTrue(
            abs(outMs - expectedMs) < DURATION_TOLERANCE_MS,
            "Duration $outMs ms should be within ${DURATION_TOLERANCE_MS}ms of $expectedMs ms",
        )
    }

    private fun createWav(durationSeconds: Int, sampleRate: Int, channels: Int): File {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val file = File(tempDir, "golden_input_${sampleRate}_${channels}ch_${durationSeconds}s.wav")
        file.writeBytes(bytes)
        return file
    }

    private companion object {
        const val EXPECTED_DURATION_2S_MS = 2_000L
        const val EXPECTED_MIN_BYTES = 24_000L // 3s at 128kbps ≈ 48KB, -50% margin
        const val EXPECTED_MAX_BYTES = 72_000L // 3s at 128kbps ≈ 48KB, +50% margin

        // Passthrough performance / size expectations.
        const val FAST_PATH_BUDGET_MS = 3_000L
        const val FAST_PATH_SIZE_RATIO_MIN = 0.90
        const val FAST_PATH_SIZE_RATIO_MAX = 1.10
    }
}
