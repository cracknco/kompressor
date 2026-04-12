package co.crackn.kompressor.video

import kotlin.math.roundToInt

/**
 * Computes target video dimensions from a [MaxResolution] constraint.
 *
 * Rules:
 * - [MaxResolution.Original] preserves the source resolution unchanged.
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
            return roundToEven(sourceWidth) to roundToEven(sourceHeight)
        }
        val scale = maxShortEdge.toFloat() / shortEdge
        // Round DOWN to even so the short edge never exceeds maxShortEdge.
        // Rounding UP (e.g. 853 → 854) would break the Custom contract when
        // maxShortEdge is odd. Floor at 2 to avoid a zero-dimension output on
        // pathological inputs (e.g. maxShortEdge < 2).
        val maxShortEven = roundDownToEven(maxShortEdge).coerceAtLeast(MIN_EVEN)
        val scaledShort = roundDownToEven((shortEdge * scale).roundToInt()).coerceIn(MIN_EVEN, maxShortEven)
        val scaledLong = roundDownToEven((maxOf(sourceWidth, sourceHeight) * scale).roundToInt())
            .coerceAtLeast(MIN_EVEN)
        return if (sourceWidth <= sourceHeight) scaledShort to scaledLong else scaledLong to scaledShort
    }

}

/** H.264 requires even width and height — round up to the nearest even integer. */
internal fun roundToEven(value: Int): Int = if (value % 2 == 0) value else value + 1

/** H.264 requires even width and height — round DOWN to the nearest even integer. */
internal fun roundDownToEven(value: Int): Int = value - (value % 2)

private const val MIN_EVEN = 2

