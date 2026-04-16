/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.property

import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.ResolutionCalculator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKotest::class)
class ResolutionCalculatorPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun outputDimensionsAreAlwaysEven() = runTest {
        checkAll(config, Arb.int(1..8000), Arb.int(1..8000)) { w, h ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.HD_720)
            assertTrue(tw % 2 == 0, "Width $tw must be even for input $w x $h")
            assertTrue(th % 2 == 0, "Height $th must be even for input $w x $h")
        }
    }

    @Test
    fun neverUpscalesBeyondSource() = runTest {
        checkAll(config, Arb.int(1..8000), Arb.int(1..8000), Arb.int(1..4000)) { w, h, target ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Custom(target))
            // Output should never exceed source (after rounding to even, allow +1 for rounding)
            assertTrue(tw <= w + 1, "Width $tw exceeds source $w for target $target")
            assertTrue(th <= h + 1, "Height $th exceeds source $h for target $target")
        }
    }

    @Test
    fun outputDimensionsAreAlwaysPositive() = runTest {
        checkAll(config, Arb.int(1..8000), Arb.int(1..8000), Arb.int(1..4000)) { w, h, target ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Custom(target))
            assertTrue(tw > 0, "Width must be positive for input $w x $h")
            assertTrue(th > 0, "Height must be positive for input $w x $h")
        }
    }

    @Test
    fun originalResolutionPreservesDimensions() = runTest {
        checkAll(config, Arb.int(1..8000), Arb.int(1..8000)) { w, h ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Original)
            // Should equal source rounded to even
            val expectedW = if (w % 2 == 0) w else w + 1
            val expectedH = if (h % 2 == 0) h else h + 1
            assertTrue(tw == expectedW, "Width $tw != expected $expectedW")
            assertTrue(th == expectedH, "Height $th != expected $expectedH")
        }
    }

    @Test
    fun aspectRatioPreservedWithinScaleRoundingTolerance() = runTest {
        // The implementation derives a scale from the *target* maxShortEdge (before rounding to
        // even) and floors each side down. That gives a ratio drift bounded by `srcRatio *
        // (ROUNDING_PX / outShort)` — proportional to the source ratio because flooring the
        // short edge by 1 pixel imparts a long-edge error proportional to srcRatio.
        checkAll(config, Arb.int(2..8000), Arb.int(2..8000), Arb.int(MIN_TARGET..MAX_TARGET)) { w, h, target ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Custom(target))
            val srcLong = maxOf(w, h).toDouble()
            val srcShort = minOf(w, h).toDouble()
            val outLong = maxOf(tw, th).toDouble()
            val outShort = minOf(tw, th).toDouble()
            val srcRatio = srcLong / srcShort
            val outRatio = outLong / outShort
            // Per-side floor of 1 pixel on each edge → at most 2 px of relative drift on the
            // short edge, scaled by srcRatio for the long edge contribution.
            val tolerance = srcRatio * (ASPECT_TOLERANCE_PIXELS.toDouble() / outShort) +
                (ASPECT_TOLERANCE_PIXELS.toDouble() / outShort)
            val diff = kotlin.math.abs(srcRatio - outRatio)
            assertTrue(
                diff <= tolerance + EPSILON,
                "Aspect ratio drifted: src=$srcRatio out=$outRatio diff=$diff tol=$tolerance " +
                    "for input ${w}x$h target=$target → ${tw}x$th",
            )
        }
    }

    @Test
    fun sourceSmallerThanConstraint_returnsEvenRoundedSource() = runTest {
        // When the shortest source edge is already <= maxShortEdge, ResolutionCalculator must
        // return the source dimensions unchanged (modulo rounding to even). No upscaling.
        // Combined Arb guarantees minOf(w, h) <= target so every iteration asserts.
        val triples = Arb.bind(
            Arb.int(2..1000),
            Arb.int(2..1000),
            Arb.int(MIN_TARGET..MAX_TARGET),
        ) { w, h, target ->
            val bumped = maxOf(target, minOf(w, h))
            Triple(w, h, bumped.coerceAtMost(MAX_TARGET))
        }
        checkAll(config, triples) { (w, h, target) ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Custom(target))
            val expectedW = if (w % 2 == 0) w else w + 1
            val expectedH = if (h % 2 == 0) h else h + 1
            assertTrue(tw == expectedW, "Expected width=$expectedW for ${w}x$h target=$target, got $tw")
            assertTrue(th == expectedH, "Expected height=$expectedH for ${w}x$h target=$target, got $th")
        }
    }

    @Test
    fun customTargetOne_producesEvenPositiveDims() = runTest {
        // MaxResolution.Custom(1) is a legal (> 0) input that exercises the MIN_EVEN floor
        // branch inside scaleToFit (maxShortEven clamped up from 0 to 2). Output must still
        // be positive-even on both axes with no division-by-zero or zero-dimension shorts.
        checkAll(config, Arb.int(2..8000), Arb.int(2..8000)) { w, h ->
            val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Custom(1))
            assertTrue(tw >= 2 && th >= 2, "Custom(1) produced non-positive dims: ${tw}x$th for ${w}x$h")
            assertTrue(tw % 2 == 0 && th % 2 == 0, "Custom(1) produced odd dims: ${tw}x$th for ${w}x$h")
        }
    }

    @Test
    fun extremeAspectRatio_doesNotCrashAndKeepsOutputPositiveEven() = runTest {
        // Pathological 65535x1 / 1x65535 inputs must still produce positive even outputs without
        // dividing by zero or emitting zero-dimension shorts.
        val (a, b) = ResolutionCalculator.calculate(EXTREME_LONG, 1, MaxResolution.Custom(MIN_TARGET))
        assertTrue(a >= 2 && b >= 2, "Extreme 65535x1 produced non-positive dims: ${a}x$b")
        assertTrue(a % 2 == 0 && b % 2 == 0, "Extreme 65535x1 produced odd dims: ${a}x$b")

        val (c, d) = ResolutionCalculator.calculate(1, EXTREME_LONG, MaxResolution.Custom(MIN_TARGET))
        assertTrue(c >= 2 && d >= 2, "Extreme 1x65535 produced non-positive dims: ${c}x$d")
        assertTrue(c % 2 == 0 && d % 2 == 0, "Extreme 1x65535 produced odd dims: ${c}x$d")
    }

    @Test
    fun onePixelInput_doesNotCrashAndOutputIsEvenPositive() = runTest {
        // 1x1 input is the minimum permitted by the require() guard. Output should be positive
        // and even on both axes — no division by zero, no zero-dim output.
        val (w, h) = ResolutionCalculator.calculate(1, 1, MaxResolution.Custom(MIN_TARGET))
        assertTrue(w >= 2, "1x1 input produced non-positive width=$w")
        assertTrue(h >= 2, "1x1 input produced non-positive height=$h")
        assertTrue(w % 2 == 0 && h % 2 == 0, "1x1 input produced odd dims: ${w}x$h")
    }

    @Test
    fun shortEdgeRespectedWhenDownscaling() = runTest {
        checkAll(config, Arb.int(100..8000), Arb.int(100..8000), Arb.int(1..99)) { w, h, target ->
            val shortEdge = minOf(w, h)
            if (shortEdge > target) {
                val (tw, th) = ResolutionCalculator.calculate(w, h, MaxResolution.Custom(target))
                val outputShortEdge = minOf(tw, th)
                // Short edge should be close to target (within rounding tolerance)
                assertTrue(
                    outputShortEdge <= target + 1,
                    "Short edge $outputShortEdge exceeds target $target + 1",
                )
            }
        }
    }

    private companion object {
        const val SEED = 12345L
        const val MIN_TARGET = 2
        const val MAX_TARGET = 4000
        const val EXTREME_LONG = 65535
        const val ASPECT_TOLERANCE_PIXELS = 2
        const val EPSILON = 1e-9
    }
}
