@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.golden

import kotlinx.cinterop.ExperimentalForeignApi
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoPresets
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden tests verify that video compression of known inputs produces outputs
 * within expected functional criteria: valid container, correct dimensions,
 * and reasonable file sizes.
 */
class GoldenVideoTest {

    private lateinit var testDir: String
    private lateinit var inputPath: String
    private val compressor = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-golden-video-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        inputPath = Mp4Generator.generateMp4(
            outputPath = testDir + "golden_input.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAMES,
            fps = INPUT_FPS,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun defaultConfig_producesValidMp4() = runTest {
        val outputPath = testDir + "golden_default.mp4"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess, "Default config failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)), "Must be valid MP4")
    }

    @Test
    fun lowBandwidthPreset_producesValidOutput() = runTest {
        val outputPath = testDir + "golden_low.mp4"

        val result = compressor.compress(inputPath, outputPath, VideoPresets.LOW_BANDWIDTH)

        assertTrue(result.isSuccess, "LOW_BANDWIDTH failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
    }

    @Test
    fun customBitrate_producesValidMp4() = runTest {
        val outputPath = testDir + "golden_custom_br.mp4"

        val result = compressor.compress(
            inputPath, outputPath,
            VideoCompressionConfig(videoBitrate = 500_000),
        )

        assertTrue(result.isSuccess, "Custom bitrate failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
        assertTrue(fileSize(outputPath) > 0, "Output must be non-empty")
    }

    @Test
    fun socialMediaPreset_producesValidOutput() = runTest {
        val outputPath = testDir + "golden_social.mp4"

        val result = compressor.compress(
            inputPath, outputPath,
            VideoPresets.SOCIAL_MEDIA,
        )

        assertTrue(result.isSuccess, "SOCIAL_MEDIA failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
    }

    private companion object {
        const val INPUT_WIDTH = 1280
        const val INPUT_HEIGHT = 720
        const val INPUT_FRAMES = 30
        const val INPUT_FPS = 30
    }
}
