package co.crackn.kompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
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
 * Asserts the output MP4's `tkhd` rotation metadata is 0 after Media3 Transformer normalises
 * frames upright.
 *
 * This test complements [VideoRotationPreservationTest], which checks the *behavioural* invariant:
 * "displayed (post-rotation) dimensions match the source." That test allows Media3 to either keep
 * the rotation tag with raw W/H or bake rotation into frames with swapped W/H — both are visually
 * identical to players. This test pins the *structural* invariant: after normalisation the container
 * must report `rotation-degrees == 0`, because the encoded frames are already upright. Catching a
 * regression where Media3 writes a non-zero `tkhd` rotation despite producing upright frames would
 * confuse downstream tooling that trusts the container metadata over the pixel content.
 */
class VideoTkhdMetadataTest {

    private lateinit var tempDir: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-tkhd-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun rotation0_tkhdRotationIsZero() = runTest {
        assertTkhdRotationZeroAfterCompression(inputRotation = 0)
    }

    @Test
    fun rotation90_tkhdRotationIsZero() = runTest {
        assertTkhdRotationZeroAfterCompression(inputRotation = 90)
    }

    @Test
    fun rotation180_tkhdRotationIsZero() = runTest {
        assertTkhdRotationZeroAfterCompression(inputRotation = 180)
    }

    @Test
    fun rotation270_tkhdRotationIsZero() = runTest {
        assertTkhdRotationZeroAfterCompression(inputRotation = 270)
    }

    private suspend fun assertTkhdRotationZeroAfterCompression(inputRotation: Int) {
        val input = Mp4Generator.generateMp4(
            output = File(tempDir, "input-$inputRotation.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = inputRotation,
        )
        val output = File(tempDir, "output-$inputRotation.mp4")
        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
        )
        assertTrue(
            result.isSuccess,
            "Compression failed for rotation=$inputRotation: ${result.exceptionOrNull()}",
        )

        val tkhdRotation = readTkhdRotation(output)
        assertEquals(
            0,
            tkhdRotation,
            "Expected tkhd rotation=0 after normalisation (input had rotation=$inputRotation)",
        )
    }

    /**
     * Reads the container-level `rotation-degrees` from the video track's [MediaFormat].
     *
     * [MediaExtractor] surfaces the `tkhd` matrix rotation as the `rotation-degrees` key
     * on the track format — the same value that [android.media.MediaMetadataRetriever]
     * reports via [android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION], but
     * accessed at the track level rather than file level.
     */
    private fun readTkhdRotation(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return format.safeInt(ROTATION_DEGREES_KEY)
                }
            }
            error("No video track in $file")
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val INPUT_WIDTH = 320
        const val INPUT_HEIGHT = 240
        const val INPUT_FRAME_COUNT = 15
        const val ROTATION_DEGREES_KEY = "rotation-degrees"
    }
}
