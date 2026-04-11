package co.crackn.kompressor.property

import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.ResolutionCalculator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
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
    }
}
