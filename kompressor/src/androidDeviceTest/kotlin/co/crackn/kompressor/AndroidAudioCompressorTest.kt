package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import android.media.MediaExtractor
import android.media.MediaFormat
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidAudioCompressorTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-audio-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressAudio_producesValidOutput() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "output.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
        )

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(output.exists())
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
    }

    @Test
    fun compressAudio_bitrateAffectsSize() = runTest {
        val input = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputLow = File(tempDir, "low.m4a")
        val outputHigh = File(tempDir, "high.m4a")

        val lowResult = compressor.compress(
            input.absolutePath,
            outputLow.absolutePath,
            AudioCompressionConfig(bitrate = 32_000),
        )
        val highResult = compressor.compress(
            input.absolutePath,
            outputHigh.absolutePath,
            AudioCompressionConfig(bitrate = 192_000),
        )
        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)
        assertTrue(outputLow.exists())
        assertTrue(outputHigh.exists())

        assertTrue(
            outputLow.length() < outputHigh.length(),
            "32kbps (${outputLow.length()}) should be < 192kbps (${outputHigh.length()})",
        )
    }

    @Test
    fun compressAudio_monoReducesSize() = runTest {
        val input = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputMono = File(tempDir, "mono.m4a")
        val outputStereo = File(tempDir, "stereo.m4a")
        val config = AudioCompressionConfig(bitrate = 64_000)

        val monoResult = compressor.compress(
            input.absolutePath,
            outputMono.absolutePath,
            config.copy(channels = AudioChannels.MONO),
        )
        val stereoResult = compressor.compress(
            input.absolutePath,
            outputStereo.absolutePath,
            config.copy(channels = AudioChannels.STEREO),
        )
        assertTrue(monoResult.isSuccess)
        assertTrue(stereoResult.isSuccess)
        assertTrue(outputMono.exists())
        assertTrue(outputStereo.exists())

        assertTrue(
            outputMono.length() <= outputStereo.length(),
            "mono (${outputMono.length()}) should be <= stereo (${outputStereo.length()})",
        )
    }

    @Test
    fun compressAudio_fileNotFound_returnsFailure() = runTest {
        val output = File(tempDir, "out.m4a")
        val result = compressor.compress("/nonexistent/audio.wav", output.absolutePath)
        assertTrue(result.isFailure)
    }

    @Test
    fun compressAudio_progressReported() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "progress.m4a")
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first(), 1e-6f)
        assertEquals(1f, progressValues.last(), 1e-6f)
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun compressAudio_48kTo44k_producesValidOutput() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_48K, STEREO)
        val output = File(tempDir, "48k_to_44k.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_44K),
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)
    }

    @Test
    fun compressAudio_44kTo22k_voiceMessage() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "voice_message.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioPresets.VOICE_MESSAGE,
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)
    }

    @Test
    fun compressAudio_stereoToMono_sameSampleRate() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "stereo_to_mono.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(channels = AudioChannels.MONO),
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)
    }

    @Test
    fun compressAudio_monoToStereo_sameSampleRate() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, MONO)
        val output = File(tempDir, "mono_to_stereo.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(channels = AudioChannels.STEREO),
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)
    }

    @Test
    fun compressAudio_sampleRateConversion_preservesDuration() = runTest {
        val durationSec = 2
        val input = createTestWavFile(durationSec, SAMPLE_RATE_48K, STEREO)
        val output = File(tempDir, "duration_check.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_22K),
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")

        val outputDurationMs = readOutputDurationMs(output)
        val expectedMs = durationSec * MS_PER_SECOND
        assertTrue(
            abs(outputDurationMs - expectedMs) < DURATION_TOLERANCE_MS,
            "Output duration $outputDurationMs ms should be within " +
                "${DURATION_TOLERANCE_MS}ms of ${expectedMs}ms",
        )
    }

    private fun readOutputDurationMs(file: File): Long {
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

    @Suppress("SameParameterValue")
    private fun createTestWavFile(durationSeconds: Int, sampleRate: Int, channels: Int): File {
        val totalSamples = sampleRate * durationSeconds
        val dataSize = totalSamples * channels * BYTES_PER_SAMPLE
        val file = File(tempDir, "test_${sampleRate}hz_${channels}ch_${durationSeconds}s.wav")

        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            // RIFF header
            out.writeBytes("RIFF")
            out.writeIntLE(WAV_HEADER_SIZE - RIFF_CHUNK_HEADER + dataSize)
            out.writeBytes("WAVE")

            // fmt sub-chunk
            out.writeBytes("fmt ")
            out.writeIntLE(PCM_FMT_CHUNK_SIZE)
            out.writeShortLE(PCM_FORMAT)
            out.writeShortLE(channels)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * channels * BYTES_PER_SAMPLE)
            out.writeShortLE(channels * BYTES_PER_SAMPLE)
            out.writeShortLE(BITS_PER_SAMPLE)

            // data sub-chunk
            out.writeBytes("data")
            out.writeIntLE(dataSize)

            // PCM sine wave at 440 Hz
            for (i in 0 until totalSamples) {
                val sample = (Short.MAX_VALUE * sin(2.0 * Math.PI * TONE_FREQUENCY * i / sampleRate)).toInt().toShort()
                for (ch in 0 until channels) {
                    out.writeShortLE(sample.toInt())
                }
            }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private companion object {
        const val SAMPLE_RATE_44K = 44_100
        const val SAMPLE_RATE_48K = 48_000
        const val SAMPLE_RATE_22K = 22_050
        const val STEREO = 2
        const val MONO = 1
        const val BYTES_PER_SAMPLE = 2
        const val BITS_PER_SAMPLE = 16
        const val PCM_FORMAT = 1
        const val PCM_FMT_CHUNK_SIZE = 16
        const val WAV_HEADER_SIZE = 44
        const val RIFF_CHUNK_HEADER = 8
        const val TONE_FREQUENCY = 440.0
        const val MS_PER_SECOND = 1_000L
        const val US_PER_MS = 1_000L
        const val DURATION_TOLERANCE_MS = 300L
    }
}
