package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.readVideoMetadata
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoPresets
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidVideoCompressorTest {

    private lateinit var tempDir: File
    private lateinit var inputFile: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-video-test").apply { mkdirs() }
        inputFile = Mp4Generator.generateMp4(
            output = File(tempDir, "input.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressVideo_producesValidMp4() = runTest {
        val output = File(tempDir, "output.mp4")

        val result = compressor.compress(
            inputPath = inputFile.absolutePath,
            outputPath = output.absolutePath,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(output.exists())
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        assertTrue(OutputValidators.isValidMp4(output.readBytes()), "Output should be valid MP4")
    }

    @Test
    fun compressVideo_downscalesTo480p() = runTest {
        val output = File(tempDir, "480p.mp4")
        val config = VideoCompressionConfig(maxResolution = MaxResolution.SD_480)

        val result = compressor.compress(
            inputPath = inputFile.absolutePath,
            outputPath = output.absolutePath,
            config = config,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val metadata = readVideoMetadata(output)
        assertTrue(metadata.width <= MAX_480P_LONG_EDGE, "Width ${metadata.width} exceeds 480p")
        assertTrue(metadata.height <= MAX_480P_SHORT_EDGE, "Height ${metadata.height} exceeds 480p")
    }

    @Test
    fun compressVideo_bitrateAffectsSize() = runTest {
        val outputLow = File(tempDir, "low_bitrate.mp4")
        val outputHigh = File(tempDir, "high_bitrate.mp4")

        val lowResult = compressor.compress(
            inputFile.absolutePath,
            outputLow.absolutePath,
            VideoCompressionConfig(videoBitrate = 200_000),
        )
        val highResult = compressor.compress(
            inputFile.absolutePath,
            outputHigh.absolutePath,
            VideoCompressionConfig(videoBitrate = 3_000_000),
        )

        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)
        assertTrue(
            outputLow.length() < outputHigh.length(),
            "Low bitrate (${outputLow.length()}) should be < high (${outputHigh.length()})",
        )
    }

    @Test
    fun compressVideo_progressReported() = runTest {
        val output = File(tempDir, "progress.mp4")
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputFile.absolutePath,
            outputPath = output.absolutePath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first(), 1e-6f)
        assertEquals(1f, progressValues.last(), 1e-6f)
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1], "Progress should be monotonic")
        }
    }

    @Test
    fun compressVideo_fileNotFound_returnsFailure() = runTest {
        val output = File(tempDir, "out.mp4")
        val result = compressor.compress("/nonexistent/video.mp4", output.absolutePath)
        assertTrue(result.isFailure)
    }

    @Test
    fun compressVideo_messagingPreset_producesValidOutput() = runTest {
        val output = File(tempDir, "messaging.mp4")
        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            VideoPresets.MESSAGING,
        )
        assertTrue(result.isSuccess, "Messaging preset failed: ${result.exceptionOrNull()}")
        assertTrue(output.exists())
        assertTrue(OutputValidators.isValidMp4(output.readBytes()))
    }

    @Test
    fun compressVideo_originalResolution_preservesDimensions() = runTest {
        val output = File(tempDir, "original.mp4")
        val config = VideoCompressionConfig(maxResolution = MaxResolution.Original)

        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            config,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val metadata = readVideoMetadata(output)
        // Allow +/- 2 for even rounding
        assertTrue(metadata.width in (INPUT_WIDTH - 2)..(INPUT_WIDTH + 2))
        assertTrue(metadata.height in (INPUT_HEIGHT - 2)..(INPUT_HEIGHT + 2))
    }

    private companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        const val INPUT_FRAME_COUNT = 30
        const val MAX_480P_LONG_EDGE = 856
        const val MAX_480P_SHORT_EDGE = 482
    }
}
