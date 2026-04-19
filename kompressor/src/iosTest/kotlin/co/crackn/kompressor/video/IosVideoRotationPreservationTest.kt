/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

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

    // One @Test per rotation per pipeline so CI surfaces the exact angle that regressed
    // instead of masking subsequent failures behind the first one.

    @Test
    fun exportSessionPath_rotation0_preserved() = runTest { assertRotationPreservedViaDefaultConfig(0) }

    @Test
    fun exportSessionPath_rotation90_preserved() = runTest { assertRotationPreservedViaDefaultConfig(90) }

    @Test
    fun exportSessionPath_rotation180_preserved() = runTest { assertRotationPreservedViaDefaultConfig(180) }

    @Test
    fun exportSessionPath_rotation270_preserved() = runTest { assertRotationPreservedViaDefaultConfig(270) }

    // The transcode path is the one we had to explicitly patch: a custom bitrate knocks us
    // off the AVAssetExportSession fast path onto AVAssetWriter, which is where
    // preferredTransform now gets forwarded.
    @Test
    fun transcodePath_rotation0_preserved() = runTest { assertRotationPreservedViaCustomConfig(0) }

    @Test
    fun transcodePath_rotation90_preserved() = runTest { assertRotationPreservedViaCustomConfig(90) }

    @Test
    fun transcodePath_rotation180_preserved() = runTest { assertRotationPreservedViaCustomConfig(180) }

    @Test
    fun transcodePath_rotation270_preserved() = runTest { assertRotationPreservedViaCustomConfig(270) }

    /**
     * Pin the fixture generator's rotation-normalisation: a negative angle must map to its
     * positive equivalent. Asserted on the fixture, not the full pipeline — the compressor
     * behaviour for 270° is already covered above.
     */
    @Test
    fun rotationNegative90_fixtureNormalisesTo270() {
        val file = Mp4Generator.generateMp4(
            outputPath = testDir + "input-neg90.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = -90,
        )
        assertEquals(270, readTrackRotation(file))
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
