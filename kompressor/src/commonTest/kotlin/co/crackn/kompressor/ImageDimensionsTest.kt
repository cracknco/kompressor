package co.crackn.kompressor

import co.crackn.kompressor.image.ImageDimensions
import co.crackn.kompressor.image.calculateInSampleSize
import co.crackn.kompressor.image.calculateTargetDimensions
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageDimensionsTest {

    // ── calculateTargetDimensions ──────────────────────────────────────

    @Test
    fun noConstraints_returnsOriginal() {
        val result = calculateTargetDimensions(1000, 800, null, null, keepAspectRatio = true)
        assertEquals(ImageDimensions(1000, 800), result)
    }

    @Test
    fun maxWidthOnly_keepAspectRatio_scalesProportionally() {
        val result = calculateTargetDimensions(2000, 1000, maxWidth = 1000, maxHeight = null, keepAspectRatio = true)
        assertEquals(ImageDimensions(1000, 500), result)
    }

    @Test
    fun maxHeightOnly_keepAspectRatio_scalesProportionally() {
        val result = calculateTargetDimensions(2000, 1000, maxWidth = null, maxHeight = 500, keepAspectRatio = true)
        assertEquals(ImageDimensions(1000, 500), result)
    }

    @Test
    fun bothConstraints_keepAspectRatio_usesMoreRestrictive() {
        // 2000x1000 → maxWidth=500, maxHeight=500
        // widthRatio = 0.25, heightRatio = 0.5 → scale = 0.25
        val result = calculateTargetDimensions(2000, 1000, maxWidth = 500, maxHeight = 500, keepAspectRatio = true)
        assertEquals(ImageDimensions(500, 250), result)
    }

    @Test
    fun bothConstraints_keepAspectRatio_heightIsMoreRestrictive() {
        // 1000x2000 → maxWidth=500, maxHeight=500
        // widthRatio = 0.5, heightRatio = 0.25 → scale = 0.25
        val result = calculateTargetDimensions(1000, 2000, maxWidth = 500, maxHeight = 500, keepAspectRatio = true)
        assertEquals(ImageDimensions(250, 500), result)
    }

    @Test
    fun noAspectRatio_clampsIndependently() {
        val result = calculateTargetDimensions(2000, 1000, maxWidth = 500, maxHeight = 800, keepAspectRatio = false)
        assertEquals(ImageDimensions(500, 800), result)
    }

    @Test
    fun noAspectRatio_onlyMaxWidth_clampsWidth() {
        val result = calculateTargetDimensions(2000, 1000, maxWidth = 500, maxHeight = null, keepAspectRatio = false)
        assertEquals(ImageDimensions(500, 1000), result)
    }

    @Test
    fun imageSmallerThanConstraints_noUpscale() {
        val result = calculateTargetDimensions(500, 300, maxWidth = 1000, maxHeight = 1000, keepAspectRatio = true)
        assertEquals(ImageDimensions(500, 300), result)
    }

    @Test
    fun imageExactlyAtConstraints_noChange() {
        val result = calculateTargetDimensions(1000, 1000, maxWidth = 1000, maxHeight = 1000, keepAspectRatio = true)
        assertEquals(ImageDimensions(1000, 1000), result)
    }

    @Test
    fun squareImage_rectangularConstraints() {
        val result = calculateTargetDimensions(1000, 1000, maxWidth = 500, maxHeight = 800, keepAspectRatio = true)
        assertEquals(ImageDimensions(500, 500), result)
    }

    @Test
    fun tinyImage_1x1() {
        val result = calculateTargetDimensions(1, 1, maxWidth = 500, maxHeight = 500, keepAspectRatio = true)
        assertEquals(ImageDimensions(1, 1), result)
    }

    @Test
    fun veryLargeDownscale_minimumIs1() {
        val result = calculateTargetDimensions(10000, 1, maxWidth = 1, maxHeight = 1, keepAspectRatio = true)
        assertEquals(1, result.width)
        assertEquals(1, result.height)
    }

    // ── calculateInSampleSize ──────────────────────────────────────────

    @Test
    fun targetSameAsOriginal_returns1() {
        assertEquals(1, calculateInSampleSize(1000, 1000, 1000, 1000))
    }

    @Test
    fun targetHalf_returns2() {
        assertEquals(2, calculateInSampleSize(1000, 1000, 500, 500))
    }

    @Test
    fun targetQuarter_returns4() {
        assertEquals(4, calculateInSampleSize(2000, 2000, 500, 500))
    }

    @Test
    fun targetMuchSmaller_returnsPowerOf2() {
        val result = calculateInSampleSize(4000, 4000, 300, 300)
        // 4000/4 = 1000 >= 300, 4000/8 = 500 >= 300, 4000/16 = 250 < 300
        assertEquals(8, result)
    }

    @Test
    fun targetLargerThanOriginal_returns1() {
        assertEquals(1, calculateInSampleSize(500, 500, 1000, 1000))
    }

    @Test
    fun asymmetricDimensions_usesMoreRestrictive() {
        // 4000x1000 → target 500x500
        // halfH=500, halfW=2000
        // 500/1>=500 && 2000/1>=500 → true → sample=2
        // 500/2>=500 → 250>=500 → false → exit
        assertEquals(2, calculateInSampleSize(4000, 1000, 500, 500))
    }
}
