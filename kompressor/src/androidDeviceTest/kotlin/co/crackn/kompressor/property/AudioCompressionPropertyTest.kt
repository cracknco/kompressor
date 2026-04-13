package co.crackn.kompressor.property

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.io.File
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalKotest::class)
class AudioCompressionPropertyTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-prop-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun randomValidConfigs_alwaysSucceed() = runTest(timeout = 5.minutes) {
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
        )
        checkAll(
            PropTestConfig(seed = SEED, iterations = ITERATIONS),
            Arb.int(32_000..192_000),
            Arb.element(22_050, 44_100, 48_000),
            Arb.element(AudioChannels.MONO, AudioChannels.STEREO),
        ) { bitrate, sampleRate, channels ->
            val config = AudioCompressionConfig(
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
            )

            val input = File(tempDir, "input_${bitrate}_${sampleRate}_${channels.count}.wav")
            input.writeBytes(wavBytes)
            val output = File(tempDir, "output_${bitrate}_${sampleRate}_${channels.count}.m4a")

            val result = compressor.compress(input.absolutePath, output.absolutePath, config)

            assertTrue(result.isSuccess, "Compression failed for config $config: ${result.exceptionOrNull()}")
            val compression = result.getOrThrow()
            assertTrue(compression.outputSize > 0, "Output size should be > 0")
            assertTrue(compression.inputSize == input.length(), "Input size mismatch")
            assertTrue(compression.durationMs >= 0, "Duration should be >= 0")

            val outputBytes = output.readBytes()
            assertTrue(OutputValidators.isValidM4a(outputBytes), "Output should be valid M4A")

            input.delete()
            output.delete()
        }
    }

    @Test
    fun progressIsMonotonicallyNonDecreasing() = runTest(timeout = 5.minutes) {
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 2,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
        )
        val input = File(tempDir, "progress_test.wav").apply { writeBytes(wavBytes) }
        val output = File(tempDir, "progress_out.m4a")
        val progressValues = mutableListOf<Float>()

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            onProgress = { progressValues.add(it) },
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")

        assertTrue(progressValues.isNotEmpty(), "Progress should be reported")
        for (i in 1 until progressValues.size) {
            assertTrue(
                progressValues[i] >= progressValues[i - 1],
                "Progress must be non-decreasing: ${progressValues[i - 1]} -> ${progressValues[i]}",
            )
        }
        assertTrue(progressValues.last() >= FINAL_PROGRESS_MIN, "Final progress should reach ~1.0")
    }

    @Test
    fun randomInputFormatCrossConfig_alwaysProducesValidM4a() = runTest(timeout = 5.minutes) {
        checkAll(
            PropTestConfig(seed = SEED, iterations = CROSS_FORMAT_ITERATIONS),
            Arb.element(InputFormat.WAV, InputFormat.AAC),
            Arb.int(64_000..160_000),
            Arb.element(22_050, 44_100),
            Arb.element(AudioChannels.MONO, AudioChannels.STEREO),
        ) { inputFormat, bitrate, sampleRate, channels ->
            val config = AudioCompressionConfig(
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
            )
            val input = when (inputFormat) {
                InputFormat.WAV -> File(tempDir, "cross_in_${System.nanoTime()}.wav").apply {
                    writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
                }
                InputFormat.AAC -> File(tempDir, "cross_in_${System.nanoTime()}.m4a").also {
                    AudioInputFixtures.createAacM4a(it, 1, SAMPLE_RATE_44K, STEREO, bitrate = 128_000)
                }
            }
            val output = File(tempDir, "cross_out_${System.nanoTime()}.m4a")

            val result = compressor.compress(input.absolutePath, output.absolutePath, config)
            assertTrue(
                result.isSuccess,
                "compress failed for ($inputFormat, $config): ${result.exceptionOrNull()}",
            )
            assertTrue(OutputValidators.isValidM4a(output.readBytes()))

            input.delete()
            output.delete()
        }
    }

    private enum class InputFormat { WAV, AAC }

    private companion object {
        val SEED: Long = System.getProperty("kompressorPropSeed")?.toLongOrNull()
            ?: kotlin.random.Random.nextLong().also {
                println("[property-seed] AudioCompressionPropertyTest: $it")
            }
        const val ITERATIONS = 15
        const val CROSS_FORMAT_ITERATIONS = 10
        const val FINAL_PROGRESS_MIN = 0.99f
    }
}
