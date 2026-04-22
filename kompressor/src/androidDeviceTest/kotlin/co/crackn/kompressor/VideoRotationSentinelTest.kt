/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.video.AndroidVideoCompressor
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pixel-content sentinel for video rotation preservation.
 *
 * [VideoRotationPreservationTest] already asserts that displayed dimensions swap correctly
 * through the compression pipeline (16x9 → 9x16). That catches "rotation was dropped" but
 * NOT "rotation was applied the wrong way": a 90° CCW rotation when the source asked for
 * 90° CW still produces dim-swapped output with the same width/height — only the pixel
 * content differs.
 *
 * This test plants distinct coloured markers in the four displayed corners via a
 * pre-rotated native pixel layout, compresses, and asserts the displayed first frame of
 * the output still has the same colours at the same displayed corners. A wrong-way 90°
 * rotation swaps two corners and the assertion fails.
 */
class VideoRotationSentinelTest {

    private lateinit var tempDir: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-rotation-sentinel-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun rotation0_displayedCornersPreserveRgbMarkers() = runTest {
        assertDisplayedCornersPreserved(rotation = 0)
    }

    @Test
    fun rotation90_displayedCornersPreserveRgbMarkers() = runTest {
        assertDisplayedCornersPreserved(rotation = 90)
    }

    private suspend fun assertDisplayedCornersPreserved(rotation: Int) {
        val input = Mp4Generator.generateCornerMarkedMp4(
            output = File(tempDir, "input-$rotation.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = rotation,
        )
        val output = File(tempDir, "output-$rotation.mp4")
        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )
        assertTrue(result.isSuccess, "Compression failed for rotation=$rotation: ${result.exceptionOrNull()}")

        val displayedFrame = extractFirstFrameInDisplayOrientation(output)
            ?: fail("Could not extract first frame from ${output.absolutePath}")

        val w = displayedFrame.width
        val h = displayedFrame.height
        assertCornerRgb(displayedFrame, x = 0, y = 0, expected = RED, label = "top-left (rotation=$rotation)")
        assertCornerRgb(displayedFrame, x = w - 1, y = 0, expected = GREEN, label = "top-right (rotation=$rotation)")
        assertCornerRgb(displayedFrame, x = 0, y = h - 1, expected = BLUE, label = "bottom-left (rotation=$rotation)")
    }

    private fun extractFirstFrameInDisplayOrientation(file: File): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame == null) {
                Log.w(
                    TAG,
                    "getFrameAtTime returned null for ${file.absolutePath} — pipeline may have " +
                        "produced an undecodable output; not skipping silently.",
                )
                return null
            }
            if (rawW == null || rawH == null) return frame
            // MediaMetadataRetriever's getFrameAtTime applies rotation on some Android versions
            // but not others; detect via dimensions instead of trusting either behaviour.
            val expectsSwap = rotation % HALF_CIRCLE == QUARTER_TURN
            val (displayW, displayH) = if (expectsSwap) rawH to rawW else rawW to rawH
            val alreadyRotated = frame.width == displayW && frame.height == displayH
            return if (alreadyRotated || rotation == 0) frame else rotateBitmap(frame, rotation)
        } finally {
            mmr.release()
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun assertCornerRgb(bitmap: Bitmap, x: Int, y: Int, expected: IntArray, label: String) {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr R_SHIFT) and BYTE_MASK
        val g = (pixel shr G_SHIFT) and BYTE_MASK
        val b = pixel and BYTE_MASK
        val dr = abs(r - expected[0])
        val dg = abs(g - expected[1])
        val db = abs(b - expected[2])
        assertTrue(
            dr <= TOLERANCE && dg <= TOLERANCE && db <= TOLERANCE,
            "Pixel at $label ($x,$y) = rgb($r,$g,$b); expected rgb(${expected[0]},${expected[1]},${expected[2]}) ±$TOLERANCE",
        )
    }

    private companion object {
        const val TAG = "VideoRotationSentinel"
        const val INPUT_WIDTH = 320
        const val INPUT_HEIGHT = 240
        const val INPUT_FRAME_COUNT = 30
        // Android MediaCodec's H.264 encoder + MediaMetadataRetriever's BT.601 limited-range
        // decode path drifts up to ~8 units per channel on the Pixel 6 (API 33) hardware — a
        // wrong-way or dropped rotation still produces ~255-unit corner swaps, so ±12 leaves
        // wide diagnostic headroom against codec quantisation noise without false positives.
        const val TOLERANCE = 12
        const val HALF_CIRCLE = 180
        const val QUARTER_TURN = 90
        const val R_SHIFT = 16
        const val G_SHIFT = 8
        const val BYTE_MASK = 0xFF

        val RED = intArrayOf(255, 0, 0)
        val GREEN = intArrayOf(0, 255, 0)
        val BLUE = intArrayOf(0, 0, 255)
    }
}
