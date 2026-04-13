package co.crackn.kompressor.property

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
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
class VideoCompressionPropertyTest {

    private lateinit var tempDir: File
    private lateinit var inputFile: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-video-prop").apply { mkdirs() }
        inputFile = Mp4Generator.generateMp4(
            output = File(tempDir, "input.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAMES,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun randomValidConfigs_alwaysProduceValidMp4() = runTest(timeout = 5.minutes) {
        checkAll(
            PropTestConfig(seed = SEED, iterations = ITERATIONS),
            Arb.int(200_000..5_000_000),
            Arb.element(MaxResolution.SD_480, MaxResolution.HD_720, MaxResolution.Original),
            Arb.int(15..60),
            Arb.int(1..5),
        ) { videoBitrate, maxResolution, maxFrameRate, keyFrameInterval ->
            val config = VideoCompressionConfig(
                videoBitrate = videoBitrate,
                maxResolution = maxResolution,
                maxFrameRate = maxFrameRate,
                keyFrameInterval = keyFrameInterval,
            )

            val output = File(
                tempDir,
                "out_${videoBitrate}_${maxFrameRate}_${keyFrameInterval}_${maxResolution}.mp4",
            )

            val result = compressor.compress(
                inputFile.absolutePath,
                output.absolutePath,
                config,
            )

            assertTrue(result.isSuccess, "Failed for config $config: ${result.exceptionOrNull()}")
            val compression = result.getOrThrow()
            assertTrue(compression.outputSize > 0, "Output must be non-empty")
            assertTrue(compression.inputSize > 0, "Input must be non-empty")
            assertTrue(compression.durationMs >= 0, "Duration must be >= 0")
            assertTrue(
                OutputValidators.isValidMp4(output.readBytes()),
                "Output must be valid MP4 for config $config",
            )

            output.delete()
        }
    }

    @Test
    fun progressIsMonotonicallyNonDecreasing() = runTest(timeout = 5.minutes) {
        val output = File(tempDir, "progress_test.mp4")
        val progressValues = mutableListOf<Float>()

        val result = compressor.compress(
            inputPath = inputFile.absolutePath,
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

    private companion object {
        // Rotate the seed on every test run so CI explores different config corners over time
        // instead of re-running the same 5 combinations forever. Echoed to logcat so a
        // reproducible bisect can plug the failing seed back in via `-PkompressorPropSeed=…`.
        val SEED: Long = System.getProperty("kompressorPropSeed")?.toLongOrNull()
            ?: kotlin.random.Random.nextLong().also {
                println("[property-seed] VideoCompressionPropertyTest: $it")
            }
        const val ITERATIONS = 5
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        const val INPUT_FRAMES = 15
        const val FINAL_PROGRESS_MIN = 0.99f
    }
}
