package co.crackn.kompressor.golden

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
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

        // Functional criteria
        assertTrue(output.exists(), "Output file must exist")
        assertTrue(compression.outputSize > 0, "Output must be non-empty")
        assertTrue(
            compression.outputSize < compression.inputSize,
            "AAC should compress PCM WAV: output=${compression.outputSize}, input=${compression.inputSize}",
        )
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        // Duration preserved within tolerance
        val outputDurationMs = readDurationMs(output)
        assertTrue(
            abs(outputDurationMs - EXPECTED_DURATION_2S_MS) < DURATION_TOLERANCE_MS,
            "Duration $outputDurationMs ms should be within ${DURATION_TOLERANCE_MS}ms of ${EXPECTED_DURATION_2S_MS}ms",
        )

        // Metadata matches default config
        val metadata = readMetadata(output)
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

        val metadata = readMetadata(output)
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
        // 3s at 128kbps ≈ 48KB, allow 50% margin → 24KB-72KB
        val expectedMinBytes = 24_000L
        val expectedMaxBytes = 72_000L
        assertTrue(
            output.length() in expectedMinBytes..expectedMaxBytes,
            "3s at 128kbps: expected ${expectedMinBytes}-${expectedMaxBytes} bytes, got ${output.length()}",
        )
    }

    private fun createWav(durationSeconds: Int, sampleRate: Int, channels: Int): File {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val file = File(tempDir, "golden_input_${sampleRate}_${channels}ch_${durationSeconds}s.wav")
        file.writeBytes(bytes)
        return file
    }

    private fun readDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return format.getLong(MediaFormat.KEY_DURATION) / US_PER_MS
                }
            }
            error("No audio track in output")
        } finally {
            extractor.release()
        }
    }

    private fun readMetadata(file: File): AudioMetadata {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return AudioMetadata(
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                }
            }
            error("No audio track in output")
        } finally {
            extractor.release()
        }
    }

    private data class AudioMetadata(val sampleRate: Int, val channels: Int)

    private companion object {
        const val EXPECTED_DURATION_2S_MS = 2_000L
        const val DURATION_TOLERANCE_MS = 300L
        const val US_PER_MS = 1_000L
    }
}
