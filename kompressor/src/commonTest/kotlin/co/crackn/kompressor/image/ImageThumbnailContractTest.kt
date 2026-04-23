/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test

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
        result.width shouldBe sourceWidth
        result.height shouldBe sourceHeight
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
        result.width shouldBe 640
        result.height shouldBe 480
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
        max(result.width, result.height) shouldBeLessThanOrEqualTo THUMBNAIL_MAX_DIMENSION
        val sourceRatio = SOURCE_WIDTH.toDouble() / SOURCE_HEIGHT
        val resultRatio = result.width.toDouble() / result.height
        val tolerance = sourceRatio / result.height + ASPECT_RATIO_TOLERANCE
        abs(sourceRatio - resultRatio) shouldBeLessThan tolerance
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
        max(result.width, result.height) shouldBeLessThanOrEqualTo THUMBNAIL_MAX_DIMENSION
        // Long edge goes to the cap, short edge scales proportionally.
        result.height shouldBe THUMBNAIL_MAX_DIMENSION
        result.width shouldBe 150
    }

    @Test
    fun calculateInSampleSizePicksPowerOfTwoForLargeThumbnailReduction() {
        // Mirrors the production dispatch exactly: `thumbnail()` → `doCompressDirect` runs
        // `calculateTargetDimensions` first to get the aspect-ratio-preserved target, then
        // feeds THAT into `calculateInSampleSize`. For a 4:3 source (4000×3000) at maxDim=200
        // the real target is (200, 150) — NOT (200, 200) — so the sample-size heuristic loops
        // one extra step: at n=16, halfHeight/n = 1500/16 = 93 < 150 → exits at 16.
        val target = calculateTargetDimensions(
            originalWidth = 4000,
            originalHeight = 3000,
            maxWidth = THUMBNAIL_MAX_DIMENSION,
            maxHeight = THUMBNAIL_MAX_DIMENSION,
            keepAspectRatio = true,
        )
        target.width shouldBe THUMBNAIL_MAX_DIMENSION
        target.height shouldBe 150
        val sample = calculateInSampleSize(
            originalWidth = 4000,
            originalHeight = 3000,
            targetWidth = target.width,
            targetHeight = target.height,
        )
        // Loop trace for target (200, 150): n=1 → halfH/1=1500 ≥ 150, advance to 2; n=2 → 750, 4;
        // n=4 → 375, 8; n=8 → 187 ≥ 150, 16; n=16 → 93 < 150 → exit. Final inSampleSize = 16.
        sample shouldBe 16
        // Sanity: at n=16 the decoded dims are 250×187 — both ≥ their respective target edges
        // (200, 150). Pass 2 (exact resize) finishes the last mile from 250×187 down to 200×150.
        ((3000 / sample) >= target.height) shouldBe true
        ((4000 / sample) >= target.width) shouldBe true
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
        sample shouldBe 1
    }

    private companion object {
        const val SOURCE_WIDTH = 640
        const val SOURCE_HEIGHT = 480
        const val THUMBNAIL_MAX_DIMENSION = 200
        const val LARGE_MAX_DIMENSION = 4_000
        const val ASPECT_RATIO_TOLERANCE = 0.02
    }
}
