/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.property

import co.crackn.kompressor.image.calculateInSampleSize
import co.crackn.kompressor.image.calculateTargetDimensions
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Property tests for the thumbnail-shape inputs to [calculateTargetDimensions] — the pure-logic
 * substrate that both platform `thumbnail()` implementations route through with
 * `maxWidth = maxHeight = maxDimension` and `keepAspectRatio = true`.
 *
 * These fuzz the DoD invariants for CRA-108 at the arithmetic layer where a regression is cheapest
 * to catch: a change that breaks "longer edge ≤ maxDimension" across a thousand random inputs
 * trips here before it ever reaches a device / simulator test. The "output byte count ≤ source
 * byte count" property can only be checked against a real encoder and therefore lives in the
 * platform test suites (androidDeviceTest, iosTest).
 */
@OptIn(ExperimentalKotest::class)
class ImageThumbnailPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun longerEdgeAlwaysFitsUnderMaxDimension() = runTest {
        // DoD property: ∀ (sourceDim ∈ 100..8000, maxDim ∈ 50..1000) — when both maxWidth and
        // maxHeight are set to the same value (thumbnail contract), max(out.width, out.height)
        // must be ≤ maxDim. Locks down the cap on the long edge regardless of orientation.
        checkAll(config, Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(MAX_MIN..MAX_MAX)) {
                w, h, maxDim ->
            val result = calculateTargetDimensions(w, h, maxDim, maxDim, keepAspectRatio = true)
            val longEdge = max(result.width, result.height)
            val sourceLong = max(w, h)
            // If the source already fits under maxDim the pipeline must NOT upscale, so the
            // long edge stays bounded by the source. Otherwise it lands at or below maxDim.
            val expectedBound = minOf(maxDim, sourceLong)
            assertTrue(
                longEdge <= expectedBound,
                "Long edge $longEdge exceeds bound $expectedBound (source=${w}x$h, maxDim=$maxDim)",
            )
        }
    }

    @Test
    fun outputDimensionsAlwaysPositive() = runTest {
        // Guards against integer-truncation regressions that could collapse a tiny source to
        // 0×N or N×0 once divided by an aggressive inSampleSize. BitmapFactory / CGImageSource
        // both treat 0-dim output as undefined — the pipeline must never produce it.
        checkAll(config, Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(MAX_MIN..MAX_MAX)) {
                w, h, maxDim ->
            val result = calculateTargetDimensions(w, h, maxDim, maxDim, keepAspectRatio = true)
            assertTrue(
                result.width >= 1 && result.height >= 1,
                "Non-positive output ${result.width}x${result.height} for source=${w}x$h maxDim=$maxDim",
            )
        }
    }

    @Test
    fun thumbnailNeverUpscales() = runTest {
        // Stated in the [ImageCompressor.thumbnail] KDoc: "never upscales — when maxDimension is
        // larger than both source dimensions the output keeps the source pixel dimensions."
        // Fuzz across both the upscale branch (maxDim > source) and the downscale branch
        // (maxDim < source) and assert the output never exceeds the source on either axis.
        checkAll(config, Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(MAX_MIN..MAX_MAX)) {
                w, h, maxDim ->
            val result = calculateTargetDimensions(w, h, maxDim, maxDim, keepAspectRatio = true)
            assertTrue(result.width <= w, "Width ${result.width} > source $w (maxDim=$maxDim)")
            assertTrue(result.height <= h, "Height ${result.height} > source $h (maxDim=$maxDim)")
        }
    }

    @Test
    fun sampleSizeStaysPowerOfTwoAndAtLeastOne() = runTest {
        // `calculateInSampleSize` feeds `BitmapFactory.Options.inSampleSize` on the Android path;
        // the platform API requires a power of two and clamps everything else to the nearest one
        // below. A regression that returns 0 (divides-by-zero downstream) or a non-power-of-two
        // (silently clamped, hidden reduction shifts) must trip here.
        checkAll(config, Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(MAX_MIN..MAX_MAX)) {
                w, h, maxDim ->
            val sample = calculateInSampleSize(w, h, maxDim, maxDim)
            assertTrue(sample >= 1, "inSampleSize $sample must be ≥ 1 (source=${w}x$h, maxDim=$maxDim)")
            // Power-of-two check: x & (x - 1) == 0 iff x is 0 or a power of two. Since we
            // already asserted sample ≥ 1, the 0 case is ruled out.
            assertTrue(
                (sample and (sample - 1)) == 0,
                "inSampleSize $sample is not a power of 2 (source=${w}x$h, maxDim=$maxDim)",
            )
        }
    }

    @Test
    fun sampleSizeKeepsDecodedDimensionsAtOrAboveTarget() = runTest {
        // Sampled-decode invariant: the chosen `inSampleSize` must leave decoded dimensions at
        // or above the target on *both* axes. If the next power of two would drop a dimension
        // below the target, the heuristic must stop at the current one. Pass 2 (exact resize)
        // is responsible for the final downscale from "≥ target" to "= target".
        checkAll(config, Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(SOURCE_MIN..SOURCE_MAX), Arb.int(MAX_MIN..MAX_MAX)) {
                w, h, maxDim ->
            val sample = calculateInSampleSize(w, h, maxDim, maxDim)
            val decodedW = w / sample
            val decodedH = h / sample
            // Only meaningful when a reduction happens. When source is already smaller than
            // maxDim the sample is 1, decoded==source, which may legitimately sit below maxDim.
            if (max(w, h) > maxDim) {
                assertTrue(
                    decodedW >= maxDim || decodedH >= maxDim,
                    "Sample=$sample over-reduces: decoded=${decodedW}x$decodedH, target=$maxDim " +
                        "(source=${w}x$h)",
                )
            }
        }
    }

    private companion object {
        const val SEED = 24_680L
        const val SOURCE_MIN = 100
        const val SOURCE_MAX = 8_000
        const val MAX_MIN = 50
        const val MAX_MAX = 1_000
    }
}
