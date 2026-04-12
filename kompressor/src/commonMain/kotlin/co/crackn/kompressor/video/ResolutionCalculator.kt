package co.crackn.kompressor.video

import kotlin.math.roundToInt

/**
 * Computes target video dimensions from a [MaxResolution] constraint.
 *
 * Rules:
 * - [MaxResolution.Original] preserves source scale (no upscaling) but still rounds
 *   odd width/height up to the nearest even number (required by H.264).
 * - [MaxResolution.Custom] scales proportionally so the shortest edge matches
 *   [MaxResolution.Custom.maxShortEdge], unless the source is already smaller
 *   (no upscaling).
 * - Output dimensions are always rounded to the nearest even number because
 *   H.264 requires even width and height.
 */
internal object ResolutionCalculator {

    /**
     * Compute target width and height for the given [sourceWidth] x [sourceHeight]
     * and [maxResolution] constraint.
     *
     * @return Pair of (targetWidth, targetHeight), both guaranteed to be positive and even.
     */
    fun calculate(sourceWidth: Int, sourceHeight: Int, maxResolution: MaxResolution): Pair<Int, Int> {
        require(sourceWidth > 0) { "sourceWidth must be > 0, was $sourceWidth" }
        require(sourceHeight > 0) { "sourceHeight must be > 0, was $sourceHeight" }

        return when (maxResolution) {
            is MaxResolution.Original -> roundToEven(sourceWidth) to roundToEven(sourceHeight)
            is MaxResolution.Custom -> scaleToFit(sourceWidth, sourceHeight, maxResolution.maxShortEdge)
        }
    }

    private fun scaleToFit(sourceWidth: Int, sourceHeight: Int, maxShortEdge: Int): Pair<Int, Int> {
        val shortEdge = minOf(sourceWidth, sourceHeight)
        if (shortEdge <= maxShortEdge) {
            return roundDownToEven(sourceWidth) to roundDownToEven(sourceHeight)
        }
        val scale = maxShortEdge.toFloat() / shortEdge
        val targetWidth = roundDownToEven((sourceWidth * scale).roundToInt())
        val targetHeight = roundDownToEven((sourceHeight * scale).roundToInt())
        return targetWidth to targetHeight
    }

    private fun roundToEven(value: Int): Int =
        if (value % 2 == 0) value else value + 1

    private fun roundDownToEven(value: Int): Int =
        when {
            value <= 2 -> 2
            value % 2 == 0 -> value
            else -> value - 1
        }
}
