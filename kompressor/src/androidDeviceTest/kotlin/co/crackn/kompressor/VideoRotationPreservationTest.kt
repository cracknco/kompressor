/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.video.AndroidVideoCompressor
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Verifies source rotation metadata is preserved through the Android Media3 `Transformer`
 * pipeline.
 *
 * Media3's frame-processor applies the source track's rotation before effects run, so the
 * encoded content ends up visually upright. The output file itself may report rotation = 0
 * while the encoded width/height swap to match the displayed orientation — or it may keep
 * the rotation tag on the `tkhd`. Either way, the *displayed* dimensions (post-rotation)
 * must match the source. This is the behavioural assertion here, since that's what "the
 * output is not rotated relative to the input" actually means to downstream players.
 */
class VideoRotationPreservationTest {

    private lateinit var tempDir: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-rotation-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun rotation0_displayedOrientationPreserved() = runTest {
        assertDisplayedOrientationPreserved(rotation = 0)
    }

    @Test
    fun rotation90_displayedOrientationPreserved() = runTest {
        assertDisplayedOrientationPreserved(rotation = 90)
    }

    @Test
    fun rotation180_displayedOrientationPreserved() = runTest {
        assertDisplayedOrientationPreserved(rotation = 180)
    }

    @Test
    fun rotation270_displayedOrientationPreserved() = runTest {
        assertDisplayedOrientationPreserved(rotation = 270)
    }

    /**
     * Pin the fixture generator's rotation-normalisation invariant: a negative angle
     * `((deg % 360) + 360) % 360`s into its positive equivalent. We assert on the fixture
     * itself rather than the full pipeline — the compressor behaviour for 270 is already
     * covered by [rotation270_displayedOrientationPreserved].
     */
    @Test
    fun rotationNegative90_fixtureNormalisesTo270() {
        val file = Mp4Generator.generateMp4(
            output = File(tempDir, "input-neg90.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = -90,
        )
        assertEquals(270, readDisplayedDimensions(file).rotation)
    }

    private suspend fun assertDisplayedOrientationPreserved(rotation: Int) {
        val input = Mp4Generator.generateMp4(
            output = File(tempDir, "input-$rotation.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = rotation,
        )
        // Sanity check: the fixture itself reports the rotation we asked for.
        val inputMeta = readDisplayedDimensions(input)
        assertEquals(rotation, inputMeta.rotation, "Generator did not tag rotation on fixture")

        val output = File(tempDir, "output-$rotation.mp4")
        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )
        assertTrue(result.isSuccess, "Compression failed for rotation=$rotation: ${result.exceptionOrNull()}")

        val outputMeta = readDisplayedDimensions(output)
        // Displayed (post-rotation) dimensions must match source. Media3 may either keep
        // the rotation tag with raw W/H, or bake rotation into frames and output swapped
        // W/H with rotation = 0. Both are visually identical to players.
        assertEquals(
            inputMeta.displayedWidth to inputMeta.displayedHeight,
            outputMeta.displayedWidth to outputMeta.displayedHeight,
            "Displayed dimensions changed for rotation=$rotation",
        )
    }

    private data class DisplayInfo(
        val rotation: Int,
        val rawWidth: Int,
        val rawHeight: Int,
        val displayedWidth: Int,
        val displayedHeight: Int,
    )

    private fun readDisplayedDimensions(file: File): DisplayInfo {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (dispW, dispH) = if (rotation % HALF_CIRCLE == QUARTER_TURN) rawH to rawW else rawW to rawH
            return DisplayInfo(rotation, rawW, rawH, dispW, dispH)
        } finally {
            mmr.release()
        }
    }

    private companion object {
        const val INPUT_WIDTH = 320
        const val INPUT_HEIGHT = 240
        const val INPUT_FRAME_COUNT = 15
        const val HALF_CIRCLE = 180
        const val QUARTER_TURN = 90
    }
}
