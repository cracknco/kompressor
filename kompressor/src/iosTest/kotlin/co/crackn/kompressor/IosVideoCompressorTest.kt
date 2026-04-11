@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import kotlinx.cinterop.ExperimentalForeignApi
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoPresets
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosVideoCompressorTest {

    private lateinit var testDir: String
    private lateinit var inputPath: String
    private val compressor = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-video-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        inputPath = Mp4Generator.generateMp4(
            outputPath = testDir + "input.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAMES,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressVideo_producesValidMp4() = runTest {
        val outputPath = testDir + "output.mp4"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        assertTrue(
            OutputValidators.isValidMp4(readBytes(outputPath)),
            "Output should be valid MP4",
        )
    }

    @Test
    fun compressVideo_downscalesTo480p() = runTest {
        val outputPath = testDir + "480p.mp4"
        val config = VideoCompressionConfig(maxResolution = MaxResolution.SD_480)

        val result = compressor.compress(inputPath, outputPath, config)

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
    }

    @Test
    fun compressVideo_customConfig_producesValidOutput() = runTest {
        val outputPath = testDir + "custom.mp4"

        val result = compressor.compress(
            inputPath, outputPath,
            VideoCompressionConfig(videoBitrate = 500_000),
        )

        assertTrue(result.isSuccess, "Custom config failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
        assertTrue(result.getOrThrow().outputSize > 0)
    }

    @Test
    fun compressVideo_progressReported() = runTest {
        val outputPath = testDir + "progress.mp4"
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
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
        val result = compressor.compress("/nonexistent/video.mp4", testDir + "out.mp4")
        assertTrue(result.isFailure)
    }

    @Test
    fun compressVideo_messagingPreset_producesValidOutput() = runTest {
        val outputPath = testDir + "messaging.mp4"
        val result = compressor.compress(inputPath, outputPath, VideoPresets.MESSAGING)
        assertTrue(result.isSuccess, "Messaging preset failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
    }

    private companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        const val INPUT_FRAMES = 30
    }
}
