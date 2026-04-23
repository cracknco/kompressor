/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the dimension-arithmetic invariants that [ImageCompressor.thumbnail]
 * must honour on every platform. The public `thumbnail(input, output, maxDimension, format,
 * quality)` method on both the Android and iOS implementations routes through
 * [calculateTargetDimensions] with `maxWidth = maxHeight = maxDimension` and
 * `keepAspectRatio = true`, so locking these invariants at the pure-logic layer catches
 * regressions in either impl without needing a device/simulator.
 *
 * Properties exercised here match the DoD of CRA-108:
 *  * `maxDimension ≥ max(source)` → no upscale — output dimensions equal source dimensions.
 *  * `maxDimension < max(source)` → longer edge clamped, shorter edge scaled proportionally.
 *  * 4:3 source → output aspect ratio within 1 px tolerance of 4:3.
 *
 * The "maxDimension ≤ 0 → Result.failure" contract is enforced at platform-level (each impl's
 * thumbnail() override does the fail-fast check) and verified by the platform test suites; it
 * cannot be exercised from commonTest without standing up a real compressor.
 */
class ImageThumbnailContractTest {

    @Test
    fun maxDimensionGreaterThanSourceLeavesDimensionsUntouched() {
        val sourceWidth = 640
        val sourceHeight = 480
        val result = calculateTargetDimensions(
            originalWidth = sourceWidth,
            originalHeight = sourceHeight,
            maxWidth = LARGE_MAX_DIMENSION,
            maxHeight = LARGE_MAX_DIMENSION,
            keepAspectRatio = true,
        )
        assertEquals(sourceWidth, result.width, "Width must not upscale")
        assertEquals(sourceHeight, result.height, "Height must not upscale")
    }

    @Test
    fun maxDimensionEqualsLongEdgeLeavesDimensionsUntouched() {
        // The "longer edge" path — when `maxDimension` matches the source's long edge exactly,
        // the scale factor lands at 1.0 and `calculateTargetDimensions` returns the input.
        val result = calculateTargetDimensions(
            originalWidth = 640,
            originalHeight = 480,
            maxWidth = 640,
            maxHeight = 640,
            keepAspectRatio = true,
        )
        assertEquals(640, result.width)
        assertEquals(480, result.height)
    }

    @Test
    fun maxDimensionSmallerThanSourceClampsLongEdgeAndPreservesAspect() {
        // 4:3 source → 4:3 output within a 1-px tolerance. A 200-px cap on the long edge (640)
        // drops the short edge to 150 (480 × 200/640). Aspect ratio checks happen at integer
        // pixel resolution so 1 px drift is acceptable.
        val result = calculateTargetDimensions(
            originalWidth = 640,
            originalHeight = 480,
            maxWidth = THUMBNAIL_MAX_DIMENSION,
            maxHeight = THUMBNAIL_MAX_DIMENSION,
            keepAspectRatio = true,
        )
        assertTrue(
            max(result.width, result.height) <= THUMBNAIL_MAX_DIMENSION,
            "Long edge ${max(result.width, result.height)} must fit under $THUMBNAIL_MAX_DIMENSION",
        )
        val sourceRatio = SOURCE_WIDTH.toDouble() / SOURCE_HEIGHT
        val resultRatio = result.width.toDouble() / result.height
        val tolerance = sourceRatio / result.height + ASPECT_RATIO_TOLERANCE
        assertTrue(
            abs(sourceRatio - resultRatio) < tolerance,
            "Aspect ratio drift: source=$sourceRatio got=$resultRatio " +
                "(${result.width}x${result.height})",
        )
    }

    @Test
    fun portraitSourceClampsOnHeight() {
        // Portrait sibling — the short-edge path exercises the same clamp on the *height* side,
        // mirror of the landscape case above. Proves the thumbnail contract is orientation-
        // symmetric (both platforms rely on `maxWidth == maxHeight` so the clamp should fire
        // on whichever edge is longer in the source).
        val result = calculateTargetDimensions(
            originalWidth = 480,
            originalHeight = 640,
            maxWidth = THUMBNAIL_MAX_DIMENSION,
            maxHeight = THUMBNAIL_MAX_DIMENSION,
            keepAspectRatio = true,
        )
        assertTrue(max(result.width, result.height) <= THUMBNAIL_MAX_DIMENSION)
        // Long edge goes to the cap, short edge scales proportionally.
        assertEquals(THUMBNAIL_MAX_DIMENSION, result.height)
        assertEquals(150, result.width)
    }

    @Test
    fun calculateInSampleSizePicksPowerOfTwoForLargeThumbnailReduction() {
        // A 48 MP source (4000×3000) with maxDimension = 200 → the heuristic uses
        // `halfDim / inSampleSize >= target` as its gate, which stops one step early of what a
        // naive "decoded ≥ target" would pick. 3000/2/8 = 187 < 200 → loop exits, returns 8.
        // Pass 2 (exact resize) then finishes the last mile from 500×375 down to 200×150.
        val sample = calculateInSampleSize(
            originalWidth = 4000,
            originalHeight = 3000,
            targetWidth = THUMBNAIL_MAX_DIMENSION,
            targetHeight = THUMBNAIL_MAX_DIMENSION,
        )
        assertEquals(8, sample, "inSampleSize should be the largest power-of-2 with halfHeight/n ≥ target")
        // Sanity: 3000 / 8 = 375 ≥ 200; at n = 16 the `halfHeight / n` gate (1500 / 16 = 93)
        // falls below 200 so the heuristic stops at 8.
        assertTrue(3000 / sample >= THUMBNAIL_MAX_DIMENSION)
        assertTrue(1500 / (sample * 2) < THUMBNAIL_MAX_DIMENSION)
    }

    @Test
    fun calculateInSampleSizeReturnsOneWhenSourceAlreadySmaller() {
        // No sampled decode needed — the 2-pass pipeline collapses to Pass 2 alone (an exact
        // final resize / no resize path, depending on dimensions). Guards against a regression
        // where a naive "inSampleSize = max(ratios)" drops below 1 and flips BitmapFactory into
        // undefined behaviour.
        val sample = calculateInSampleSize(
            originalWidth = 100,
            originalHeight = 100,
            targetWidth = THUMBNAIL_MAX_DIMENSION,
            targetHeight = THUMBNAIL_MAX_DIMENSION,
        )
        assertEquals(1, sample)
    }

    private companion object {
        const val SOURCE_WIDTH = 640
        const val SOURCE_HEIGHT = 480
        const val THUMBNAIL_MAX_DIMENSION = 200
        const val LARGE_MAX_DIMENSION = 4_000
        const val ASPECT_RATIO_TOLERANCE = 0.02
    }
}
