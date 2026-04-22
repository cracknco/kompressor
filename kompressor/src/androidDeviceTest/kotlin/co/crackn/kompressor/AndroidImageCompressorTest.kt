/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createTestImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidImageCompressorTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressImage_producesValidOutput() = runTest {
        val input = createTestImage(tempDir, 1000, 1000)
        val output = File(tempDir, "output.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(output.exists())
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        assertTrue(
            compression.compressionRatio < 1f,
            "JPEG should compress PNG input: ratio=${compression.compressionRatio}",
        )
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output should be valid JPEG")
    }

    /**
     * Parameterised sweep of every EXIF orientation the compressor's when-expression in
     * `AndroidImageCompressor.applyExifRotation` handles. Each case asserts:
     *   1. Output exists and is valid JPEG.
     *   2. Output dimensions match the expected post-rotation dims (swapped for the 90°
     *      and 270° cases, preserved for the 180° and flip cases).
     *   3. A sentinel pixel from the input's distinctive quadrant lands in the right
     *      destination quadrant — the only gate that catches a "swap metadata, skip the
     *      actual matrix transform" regression.
     */
    @Test
    fun exifOrientation_rotate90_swapsDimensionsAndRotatesPixels() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90,
            srcW = 200, srcH = 100, expectedW = 100, expectedH = 200,
            // Top-left of input (red) ends up in the top-right after 90° CW rotation.
            sentinelDestCorner = Corner.TOP_RIGHT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    @Test
    fun exifOrientation_rotate180_preservesDimensionsAndRotatesPixels() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180,
            srcW = 200, srcH = 100, expectedW = 200, expectedH = 100,
            // Top-left of input ends up in the bottom-right after 180° rotation.
            sentinelDestCorner = Corner.BOTTOM_RIGHT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    @Test
    fun exifOrientation_rotate270_swapsDimensionsAndRotatesPixels() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270,
            srcW = 200, srcH = 100, expectedW = 100, expectedH = 200,
            // Top-left of input ends up in the bottom-left after 270° CW rotation.
            sentinelDestCorner = Corner.BOTTOM_LEFT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    @Test
    fun exifOrientation_flipHorizontal_preservesDimensionsAndMirrorsPixels() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            srcW = 200, srcH = 100, expectedW = 200, expectedH = 100,
            // FLIP_HORIZONTAL mirrors left/right → top-left → top-right.
            sentinelDestCorner = Corner.TOP_RIGHT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    @Test
    fun exifOrientation_flipVertical_preservesDimensionsAndMirrorsPixels() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL,
            srcW = 200, srcH = 100, expectedW = 200, expectedH = 100,
            // FLIP_VERTICAL mirrors up/down → top-left → bottom-left.
            sentinelDestCorner = Corner.BOTTOM_LEFT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    @Test
    fun exifOrientation_transpose_swapsDimensionsAndFlipsAlongMainDiagonal() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE,
            srcW = 200, srcH = 100, expectedW = 100, expectedH = 200,
            // TRANSPOSE = flip along the top-left→bottom-right diagonal. Top-left stays
            // top-left; top-right → bottom-left. Use TOP_LEFT as the stable sentinel.
            sentinelDestCorner = Corner.TOP_LEFT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    @Test
    fun exifOrientation_transverse_swapsDimensionsAndFlipsAlongAntiDiagonal() =
        assertExifRotationEndToEnd(
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE,
            srcW = 200, srcH = 100, expectedW = 100, expectedH = 200,
            // TRANSVERSE = flip along the top-right→bottom-left diagonal. Top-left → bottom-right.
            sentinelDestCorner = Corner.BOTTOM_RIGHT,
            sentinelColor = co.crackn.kompressor.testutil.TOP_LEFT_COLOR,
        )

    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private fun assertExifRotationEndToEnd(
        exifOrientation: Int,
        srcW: Int,
        srcH: Int,
        expectedW: Int,
        expectedH: Int,
        sentinelDestCorner: Corner,
        sentinelColor: Int,
    ) = runTest {
        val input = co.crackn.kompressor.testutil.createExifTaggedJpeg(
            tempDir, width = srcW, height = srcH, exifOrientation = exifOrientation,
        )
        val output = File(tempDir, "rotated_${exifOrientation}_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be valid JPEG")

        val bmp = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
            ?: error("Output couldn't be decoded as bitmap")
        try {
            assertEquals(expectedW, bmp.width, "Dimension mismatch for orientation=$exifOrientation")
            assertEquals(expectedH, bmp.height, "Dimension mismatch for orientation=$exifOrientation")

            // Sample a pixel well inside the expected destination quadrant (avoid the JPEG
            // block boundary noise at the edge by sampling at quarter/three-quarters).
            val sampleX = when (sentinelDestCorner) {
                Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> bmp.width / 4
                Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> (bmp.width * 3) / 4
            }
            val sampleY = when (sentinelDestCorner) {
                Corner.TOP_LEFT, Corner.TOP_RIGHT -> bmp.height / 4
                Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> (bmp.height * 3) / 4
            }
            val sampled = bmp.getPixel(sampleX, sampleY)
            assertPixelsApproximatelyEqual(
                expected = sentinelColor,
                actual = sampled,
                tolerance = JPEG_COMPONENT_TOLERANCE,
                context = "orientation=$exifOrientation at ($sampleX,$sampleY) " +
                    "expected corner=$sentinelDestCorner",
            )
        } finally {
            bmp.recycle()
        }
    }

    private fun assertPixelsApproximatelyEqual(expected: Int, actual: Int, tolerance: Int, context: String) {
        val er = android.graphics.Color.red(expected)
        val eg = android.graphics.Color.green(expected)
        val eb = android.graphics.Color.blue(expected)
        val ar = android.graphics.Color.red(actual)
        val ag = android.graphics.Color.green(actual)
        val ab = android.graphics.Color.blue(actual)
        assertTrue(
            kotlin.math.abs(er - ar) <= tolerance &&
                kotlin.math.abs(eg - ag) <= tolerance &&
                kotlin.math.abs(eb - ab) <= tolerance,
            "Pixel mismatch: expected rgb($er,$eg,$eb) got rgb($ar,$ag,$ab) — $context",
        )
    }

    @Test
    fun compressImage_withResize_reducesDimensions() = runTest {
        val input = createTestImage(tempDir, 2000, 1000)
        val output = File(tempDir, "resized.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config = ImageCompressionConfig(maxWidth = 500, maxHeight = 500),
        )

        assertTrue(result.isSuccess)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        assertEquals(500, options.outWidth)
        assertEquals(250, options.outHeight)
    }

    @Test
    fun compressImage_qualityAffectsSize() = runTest {
        val input = createTestImage(tempDir, 1000, 1000)
        val outputLow = File(tempDir, "low.jpg")
        val outputMid = File(tempDir, "mid.jpg")
        val outputHigh = File(tempDir, "high.jpg")

        compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(outputLow.absolutePath),
            ImageCompressionConfig(quality = 10,
        ))
        compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(outputMid.absolutePath),
            ImageCompressionConfig(quality = 50,
        ))
        compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(outputHigh.absolutePath),
            ImageCompressionConfig(quality = 95,
        ))

        assertTrue(outputLow.length() < outputMid.length())
        assertTrue(outputMid.length() < outputHigh.length())
    }

    @Test
    fun compressImage_fileNotFound_returnsFailure() = runTest {
        val output = File(tempDir, "out.jpg")
        val result = compressor.compress(
            MediaSource.Local.FilePath("/nonexistent/image.png"),
            MediaDestination.Local.FilePath(output.absolutePath),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun compressImage_cancellationAbortsWorkAndLeavesNoPartialOutput() = kotlinx.coroutines.runBlocking {
        // Image compression isn't streaming — the whole bitmap is decoded in one shot —
        // but `AndroidImageCompressor.compress()` is a `suspend` function that calls
        // `currentCoroutineContext().ensureActive()` between the raw decode and the resize.
        // Cancellation must propagate through that yield point, and the suspendRunCatching
        // wrapper must surface the cancel as `Result.failure` without leaving a partial
        // file on disk (image compression doesn't use `deletingOutputOnFailure` today, but
        // the output write is still conditional on passing the ensureActive check).
        val input = createTestImage(tempDir, 3000, 3000)
        val output = File(tempDir, "cancel_image_out.jpg")
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job(),
        )
        val job = scope.launch {
            compressor.compress(
                MediaSource.Local.FilePath(input.absolutePath),
                MediaDestination.Local.FilePath(output.absolutePath),
            )
        }
        // Cancel immediately — the decode on a 3000×3000 PNG takes long enough on any device
        // that the first ensureActive() gate should trip before resize + write can start.
        job.cancel()
        kotlinx.coroutines.withTimeout(5_000L) { job.join() }

        // After cancel + join, either:
        //   (a) cancel landed before the write step — output doesn't exist, or
        //   (b) compress completed before cancel was observed — output exists and must be
        //       a fully-valid JPEG (a truncated / malformed output is the regression we're
        //       guarding against — image compression doesn't use `deletingOutputOnFailure`,
        //       so any file that does exist must have been produced by a complete write).
        // `job.isCancelled` isn't useful as a discriminator here: it's always `true` after
        // `job.cancel()` irrespective of whether the body had time to run to completion.
        if (output.exists()) {
            assertTrue(
                OutputValidators.isValidJpeg(output.readBytes()),
                "If output exists after cancel, it must be complete JPEG, not truncated",
            )
        }
    }


    private companion object {
        /**
         * JPEG quantisation blurs flat-colour blocks by a handful of levels per channel. 24
         * is tight enough to reject "my output is mostly green" if we expected red (those
         * would differ by ~200 on one channel), loose enough to tolerate the per-channel drift
         * on real JPEGs encoded at quality ~85-95.
         */
        const val JPEG_COMPONENT_TOLERANCE = 24
    }
}
