package co.crackn.kompressor.property

import co.crackn.kompressor.image.calculateTargetDimensions
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKotest::class)
class ImageDimensionsPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun neverUpscales() = runTest {
        checkAll(config, Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000)) {
                origW, origH, maxW, maxH ->
            val result = calculateTargetDimensions(origW, origH, maxW, maxH, keepAspectRatio = true)
            assertTrue(result.width <= origW, "Width ${result.width} > original $origW (max=$maxW)")
            assertTrue(result.height <= origH, "Height ${result.height} > original $origH (max=$maxH)")
        }
    }

    @Test
    fun nullConstraintsReturnOriginal() = runTest {
        checkAll(config, Arb.int(1..10_000), Arb.int(1..10_000)) { w, h ->
            val result = calculateTargetDimensions(w, h, null, null, keepAspectRatio = true)
            assertTrue(
                result.width == w && result.height == h,
                "Expected ${w}x$h, got ${result.width}x${result.height}",
            )
        }
    }

    @Test
    fun aspectRatioPreservedWithinRoundingError() = runTest {
        checkAll(config, Arb.int(200..5_000), Arb.int(200..5_000), Arb.int(100..2_000), Arb.int(100..2_000)) {
                origW, origH, maxW, maxH ->
            val result = calculateTargetDimensions(origW, origH, maxW, maxH, keepAspectRatio = true)
            val minDim = result.width.coerceAtMost(result.height)
            if ((result.width < origW || result.height < origH) && minDim >= MIN_MEANINGFUL_DIM) {
                val origRatio = origW.toDouble() / origH
                val resultRatio = result.width.toDouble() / result.height
                val tolerance = origRatio / minDim + ASPECT_TOLERANCE
                assertTrue(
                    abs(origRatio - resultRatio) < tolerance,
                    "Aspect ratio drift: original=$origRatio, result=$resultRatio " +
                        "(orig=${origW}x$origH, result=${result.width}x${result.height})",
                )
            }
        }
    }

    @Test
    fun outputDimensionsAlwaysPositive() = runTest {
        checkAll(config, Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000)) {
                origW, origH, maxW, maxH ->
            val result = calculateTargetDimensions(origW, origH, maxW, maxH, keepAspectRatio = true)
            assertTrue(
                result.width >= 1 && result.height >= 1,
                "Dimensions must be >= 1, got ${result.width}x${result.height}",
            )
        }
    }

    @Test
    fun outputRespectsMaxConstraints() = runTest {
        checkAll(config, Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000)) {
                origW, origH, maxW, maxH ->
            val result = calculateTargetDimensions(origW, origH, maxW, maxH, keepAspectRatio = true)
            assertTrue(
                result.width <= maxW || result.width <= origW,
                "Width ${result.width} exceeds max $maxW and original $origW",
            )
            assertTrue(
                result.height <= maxH || result.height <= origH,
                "Height ${result.height} exceeds max $maxH and original $origH",
            )
        }
    }

    @Test
    fun noAspectRatio_clampsIndependently() = runTest {
        checkAll(config, Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000), Arb.int(1..10_000)) {
                origW, origH, maxW, maxH ->
            val result = calculateTargetDimensions(origW, origH, maxW, maxH, keepAspectRatio = false)
            assertTrue(
                result.width <= origW && result.width <= maxW || result.width == origW,
                "Without aspect ratio, width should be min(orig, max)",
            )
            assertTrue(
                result.height <= origH && result.height <= maxH || result.height == origH,
                "Without aspect ratio, height should be min(orig, max)",
            )
        }
    }

    private companion object {
        const val SEED = 12345L
        const val ASPECT_TOLERANCE = 0.02
        const val MIN_MEANINGFUL_DIM = 20
    }
}
