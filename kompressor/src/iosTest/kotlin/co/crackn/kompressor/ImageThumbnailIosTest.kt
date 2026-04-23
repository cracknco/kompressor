/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createExifTaggedJpeg
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIImage
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simulator end-to-end tests for [IosImageCompressor.thumbnail]. Sibling of
 * `ImageThumbnailDeviceTest` on Android — same DoD invariants, exercised through the
 * `CGImageSourceCreateThumbnailAtIndex` sampled-decode path instead of BitmapFactory.
 *
 * Properties verified:
 *  * 4000×3000 PNG → ≤ 200×200 JPEG, 4:3 aspect preserved to the pixel.
 *  * `maxDimension ≤ 0` → `Result.failure(IllegalArgumentException)`.
 *  * Source already smaller than `maxDimension` → output keeps source pixel dimensions.
 *  * Fixture with embedded `kCGImagePropertyOrientation = 6` → thumbnail applies the transform
 *    because the iOS impl passes `kCGImageSourceCreateThumbnailWithTransform = true`.
 */
@OptIn(ExperimentalForeignApi::class)
class ImageThumbnailIosTest {

    private lateinit var testDir: String
    private val compressor = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-thumbnail-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun thumbnail_largeSource_clampsLongEdgeToMaxDimension() = runTest {
        val input = createTestImage(testDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = testDir + "thumb_4000x3000.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(readBytes(output)), "Output should be valid JPEG")

        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        val w = CGImageGetWidth(cgImage).toInt()
        val h = CGImageGetHeight(cgImage).toInt()
        assertTrue(
            max(w, h) <= MAX_DIMENSION,
            "Long edge ${max(w, h)} must fit under $MAX_DIMENSION",
        )
        // 4000×3000 (4:3) capped at 200 → long edge 200, short edge 150 exactly. CGImageSource's
        // sampled-decode math rounds to integers the same way Android's Bitmap.createScaledBitmap
        // does at this ratio, so dimensions line up byte-for-byte between the two platforms.
        assertEquals(MAX_DIMENSION, w)
        assertEquals(MAX_SHORT_EDGE, h)
    }

    @Test
    fun thumbnail_exifRotate_swapsOrientedDimensions() = runTest {
        // Fixture is 200×100 (landscape) with `kCGImagePropertyOrientation = 6` ("Right" —
        // visual 90° CW). With `kCGImageSourceCreateThumbnailWithTransform = true`, CGImageSource
        // applies the rotation inside the thumbnail decode — output must be 100×200 (portrait).
        // A regression that drops the transform flag would produce a 200×100 output; a regression
        // that ignores orientation entirely would produce 100×200 but with the wrong pixel content
        // (not checked here — dimension swap is the cheaper canary).
        val input = createExifTaggedJpeg(testDir, EXIF_SRC_WIDTH, EXIF_SRC_HEIGHT, orientation = EXIF_RIGHT)
        val output = testDir + "thumb_exif.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        val w = CGImageGetWidth(cgImage).toInt()
        val h = CGImageGetHeight(cgImage).toInt()
        assertTrue(
            max(w, h) <= MAX_DIMENSION,
            "Long edge ${max(w, h)} must fit under $MAX_DIMENSION",
        )
        // Post-transform dims: 100×200. maxDim=200 caps the long edge but the source is already
        // at 200 on the long edge after orientation is applied, so no downscale fires.
        assertEquals(EXIF_SRC_HEIGHT, w)
        assertEquals(EXIF_SRC_WIDTH, h)
    }

    @Test
    fun thumbnail_zeroMaxDimension_returnsFailureWithoutTouchingSource() = runTest {
        val input = createTestImage(testDir, 500, 500)
        val output = testDir + "thumb_zero.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = 0,
        )

        assertTrue(result.isFailure, "Expected failure for maxDimension=0")
        assertTrue(
            result.exceptionOrNull() is IllegalArgumentException,
            "Expected IllegalArgumentException, got ${result.exceptionOrNull()}",
        )
        assertTrue(
            !NSFileManager.defaultManager.fileExistsAtPath(output),
            "No output file should be created when args fail validation",
        )
    }

    @Test
    fun thumbnail_negativeMaxDimension_returnsFailure() = runTest {
        val input = createTestImage(testDir, 500, 500)
        val output = testDir + "thumb_neg.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = -1,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(!NSFileManager.defaultManager.fileExistsAtPath(output))
    }

    @Test
    fun thumbnail_sourceSmallerThanMax_keepsSourceDimensions() = runTest {
        // KDoc contract: "never upscales — when maxDimension is larger than both source dims
        // the output keeps the source pixel dimensions." CGImageSource honours
        // kCGImageSourceThumbnailMaxPixelSize as an upper bound only; it does not scale up.
        val input = createTestImage(testDir, SMALL_DIM, SMALL_DIM)
        val output = testDir + "thumb_small.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        assertTrue(result.isSuccess, "thumbnail failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(readBytes(output)))
        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        assertEquals(SMALL_DIM, CGImageGetWidth(cgImage).toInt())
        assertEquals(SMALL_DIM, CGImageGetHeight(cgImage).toInt())
    }

    private companion object {
        const val LARGE_WIDTH = 4_000
        const val LARGE_HEIGHT = 3_000
        const val MAX_DIMENSION = 200
        // 4:3 source at long edge 200 → short edge = 200 * 3 / 4 = 150.
        const val MAX_SHORT_EDGE = 150
        const val EXIF_SRC_WIDTH = 200
        const val EXIF_SRC_HEIGHT = 100
        const val EXIF_RIGHT = 6
        const val SMALL_DIM = 100
    }
}
