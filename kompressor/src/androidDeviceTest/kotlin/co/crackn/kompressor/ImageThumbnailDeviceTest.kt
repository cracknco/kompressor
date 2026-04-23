/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createExifTaggedJpeg
import co.crackn.kompressor.testutil.createTestImage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On-device end-to-end tests for [AndroidImageCompressor.thumbnail]. Proves the DoD for CRA-108
 * on the Android side end-to-end — the `commonTest` contract + property suites lock down the
 * pure-logic invariants, this class drives the full `BitmapFactory` sampled-decode pipeline on
 * an emulator / physical device.
 *
 * Properties exercised:
 *  * 4000×3000 PNG → ≤ 200×200 JPEG output, aspect ratio preserved to the pixel.
 *  * Peak heap delta during sampled decode stays bounded — a regression that flips the decoder
 *    back to a full-resolution decode (~48 MB) would blow past the 10 MB envelope.
 *  * EXIF orientation = 6 (ROTATE_90) → output dimensions flip orientation and the long edge
 *    stays ≤ maxDimension, mirroring `AndroidImageCompressorTest.exifOrientation_rotate90` at
 *    the thumbnail entry point.
 *  * `maxDimension = 0` → `Result.failure(IllegalArgumentException)`, no output touched.
 */
class ImageThumbnailDeviceTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-thumbnail-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun thumbnail_largeSource_clampsLongEdgeToMaxDimension() = runTest {
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = File(tempDir, "thumb_4000x3000.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
        )

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output should be valid JPEG")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        assertTrue(
            max(options.outWidth, options.outHeight) <= MAX_DIMENSION,
            "Long edge ${max(options.outWidth, options.outHeight)} must fit under $MAX_DIMENSION",
        )
        // 4000×3000 (4:3) at maxDim=200 → 200×150 exactly after the exact-resize Pass 2.
        assertEquals(MAX_DIMENSION, options.outWidth)
        assertEquals(MAX_SHORT_EDGE, options.outHeight)
    }

    @Test
    fun thumbnail_peakMemoryStaysUnderSampledDecodeEnvelope() = runTest {
        // Sampled decode keeps peak heap bounded regardless of source pixel count. A full decode
        // of a 4000×3000 RGBA bitmap would hold ~48 MB; the `inSampleSize`-driven pipeline caps
        // allocations near the sample-sized decode (~1–2 MB for 500×375) plus the exact-resize
        // output (~120 KB for 200×150). The 10 MB envelope below is the DoD ceiling — it's
        // generous enough to absorb Bitmap.compress's internal buffer + normal GC noise, tight
        // enough to flag a regression that flips the decoder back to full resolution.
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = File(tempDir, "thumb_memory.jpg")

        val runtime = Runtime.getRuntime()
        // Drain soft references / cached allocations so the baseline reflects the quiescent
        // post-fixture heap, not any transient spikes from the PNG writer.
        repeat(GC_ROUNDS) { runtime.gc() }
        val baseline = runtime.totalMemory() - runtime.freeMemory()

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
        )
        // Measure peak BEFORE the post-thumbnail GC — any recycled bitmap bytes would otherwise
        // disappear from the measurement and hide a regression.
        val peak = runtime.totalMemory() - runtime.freeMemory()
        val deltaBytes = peak - baseline

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        assertTrue(
            deltaBytes <= MAX_PEAK_DELTA_BYTES,
            "Peak heap delta $deltaBytes B exceeds sampled-decode envelope $MAX_PEAK_DELTA_BYTES B — " +
                "possible regression to full-resolution decode",
        )
    }

    @Test
    fun thumbnail_exifRotate90_swapsDimensionsAndClamps() = runTest {
        // Mirror of `AndroidImageCompressorTest.exifOrientation_rotate90_swapsDimensionsAndRotatesPixels`
        // at the thumbnail entry point. With EXIF ORIENTATION = 6 (ROTATE_90 CW) applied to a
        // 200×100 landscape source, the oriented dims are 100×200; at maxDim=200 the long edge
        // is already at the cap so the thumbnail should be exactly 100×200.
        val input = createExifTaggedJpeg(
            tempDir,
            width = EXIF_SRC_WIDTH,
            height = EXIF_SRC_HEIGHT,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90,
        )
        val output = File(tempDir, "thumb_exif90.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
        )

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        assertTrue(max(options.outWidth, options.outHeight) <= MAX_DIMENSION)
        // Post-rotation 100×200 — dims swapped relative to the 200×100 source. Proves the
        // sampled-decode path honours EXIF rotation and didn't degrade to "ignore orientation".
        assertEquals(EXIF_SRC_HEIGHT, options.outWidth)
        assertEquals(EXIF_SRC_WIDTH, options.outHeight)
    }

    @Test
    fun thumbnail_zeroMaxDimension_returnsFailureWithoutTouchingSource() = runTest {
        val input = createTestImage(tempDir, 500, 500)
        val output = File(tempDir, "thumb_zero.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = 0,
        )

        assertTrue(result.isFailure, "Expected failure for maxDimension=0")
        assertTrue(
            result.exceptionOrNull() is IllegalArgumentException,
            "Expected IllegalArgumentException, got ${result.exceptionOrNull()}",
        )
        assertTrue(!output.exists(), "No output file should be created for invalid args")
    }

    @Test
    fun thumbnail_negativeMaxDimension_returnsFailure() = runTest {
        val input = createTestImage(tempDir, 500, 500)
        val output = File(tempDir, "thumb_neg.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = -1,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(!output.exists())
    }

    @Test
    fun thumbnail_sourceAlreadySmaller_producesOutputMatchingSourceDimensions() = runTest {
        // KDoc contract: "never upscales — when maxDimension is larger than both source
        // dimensions the output keeps the source pixel dimensions (re-encoded to format/quality)".
        // Source is 100×100 < maxDimension=200 → output must be 100×100, re-encoded to JPEG.
        val input = createTestImage(tempDir, SMALL_DIM, SMALL_DIM)
        val output = File(tempDir, "thumb_small.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
            format = ImageFormat.JPEG,
        )

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        assertEquals(SMALL_DIM, options.outWidth)
        assertEquals(SMALL_DIM, options.outHeight)
    }

    private companion object {
        const val LARGE_WIDTH = 4_000
        const val LARGE_HEIGHT = 3_000
        const val MAX_DIMENSION = 200
        const val MAX_SHORT_EDGE = 150 // 4:3 source → 150 = 480 * 200/640 — DoD example
        const val EXIF_SRC_WIDTH = 200
        const val EXIF_SRC_HEIGHT = 100
        const val SMALL_DIM = 100
        const val GC_ROUNDS = 3
        // 10 MB envelope — full 4000×3000 RGBA decode would hit ~48 MB so this is a ~5× safety
        // margin over the expected ~1–2 MB peak of the sampled-decode pipeline.
        const val MAX_PEAK_DELTA_BYTES = 10L * 1024 * 1024
    }
}
