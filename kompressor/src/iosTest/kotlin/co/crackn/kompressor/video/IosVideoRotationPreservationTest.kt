@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.computeRotationDegrees
import co.crackn.kompressor.testutil.Mp4Generator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * Verifies that both iOS video pipelines preserve the source track's rotation:
 * - [IosVideoExportPipeline] via `AVAssetExportSession` (default config path)
 * - [IosVideoTranscodePipeline] via `AVAssetWriter` (custom config path)
 *
 * Rotation is encoded in the track's `preferredTransform` as a `CGAffineTransform`.
 * We decode the angle back out via [computeRotationDegrees] and assert equality with
 * the fixture input, for each of 0°/90°/180°/270°.
 */
class IosVideoRotationPreservationTest {

    private lateinit var testDir: String
    private val compressor = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-rotation-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun exportSessionPath_preservesAllRotations() = runTest {
        // Default config drives the AVAssetExportSession fast path.
        listOf(0, 90, 180, 270).forEach { rotation ->
            assertRotationPreservedViaDefaultConfig(rotation)
        }
    }

    @Test
    fun transcodePath_preservesAllRotations() = runTest {
        // A custom bitrate forces the AVAssetWriter path — this is the path where
        // we had to explicitly forward preferredTransform onto the writer input.
        listOf(0, 90, 180, 270).forEach { rotation ->
            assertRotationPreservedViaCustomConfig(rotation)
        }
    }

    private suspend fun assertRotationPreservedViaDefaultConfig(rotation: Int) {
        val input = Mp4Generator.generateMp4(
            outputPath = testDir + "input-export-$rotation.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = rotation,
        )
        assertEquals(rotation, readTrackRotation(input), "Generator did not tag rotation=$rotation")

        val output = testDir + "output-export-$rotation.mp4"
        val result = compressor.compress(inputPath = input, outputPath = output)
        assertTrue(
            result.isSuccess,
            "Export path failed for rotation=$rotation: ${result.exceptionOrNull()}",
        )
        assertEquals(rotation, readTrackRotation(output), "Export path lost rotation=$rotation")
    }

    private suspend fun assertRotationPreservedViaCustomConfig(rotation: Int) {
        val input = Mp4Generator.generateMp4(
            outputPath = testDir + "input-transcode-$rotation.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = rotation,
        )
        assertEquals(rotation, readTrackRotation(input), "Generator did not tag rotation=$rotation")

        val output = testDir + "output-transcode-$rotation.mp4"
        // Any non-default config knocks us off the export-session fast path.
        val config = VideoCompressionConfig(videoBitrate = CUSTOM_BITRATE)
        val result = compressor.compress(inputPath = input, outputPath = output, config = config)
        assertTrue(
            result.isSuccess,
            "Transcode path failed for rotation=$rotation: ${result.exceptionOrNull()}",
        )
        assertEquals(rotation, readTrackRotation(output), "Transcode path lost rotation=$rotation")
    }

    private fun readTrackRotation(path: String): Int {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: error("No video track found in $path")
        return track.preferredTransform.useContents { computeRotationDegrees(a, b) }
    }

    private companion object {
        const val INPUT_WIDTH = 320
        const val INPUT_HEIGHT = 240
        const val INPUT_FRAME_COUNT = 15
        const val CUSTOM_BITRATE = 400_000
    }
}
