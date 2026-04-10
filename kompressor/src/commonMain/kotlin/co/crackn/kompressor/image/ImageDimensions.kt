package co.crackn.kompressor.image

import kotlin.math.min
import kotlin.math.roundToInt

/** Width and height of an image in pixels. */
internal data class ImageDimensions(val width: Int, val height: Int)

/**
 * Calculate target dimensions for resizing, respecting [maxWidth], [maxHeight],
 * and [keepAspectRatio]. Never upscales (returns original dimensions when the
 * image already fits within the constraints).
 */
internal fun calculateTargetDimensions(
    originalWidth: Int,
    originalHeight: Int,
    maxWidth: Int?,
    maxHeight: Int?,
    keepAspectRatio: Boolean,
): ImageDimensions = when {
    maxWidth == null && maxHeight == null -> ImageDimensions(originalWidth, originalHeight)
    !keepAspectRatio -> ImageDimensions(
        width = clampDimension(originalWidth, maxWidth),
        height = clampDimension(originalHeight, maxHeight),
    )
    else -> {
        val widthRatio = maxWidth?.let { it.toFloat() / originalWidth } ?: Float.MAX_VALUE
        val heightRatio = maxHeight?.let { it.toFloat() / originalHeight } ?: Float.MAX_VALUE
        val scale = min(widthRatio, heightRatio)
        if (scale >= 1f) {
            ImageDimensions(originalWidth, originalHeight)
        } else {
            ImageDimensions(
                width = (originalWidth * scale).roundToInt().coerceAtLeast(1),
                height = (originalHeight * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
}

/**
 * Calculate the largest power-of-2 sample size such that the decoded image is
 * still at least as large as the target dimensions. Used by Android's
 * [android.graphics.BitmapFactory.Options.inSampleSize].
 */
internal fun calculateInSampleSize(
    originalWidth: Int,
    originalHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    var inSampleSize = 1
    if (originalHeight > targetHeight || originalWidth > targetWidth) {
        val halfHeight = originalHeight / 2
        val halfWidth = originalWidth / 2
        while (halfHeight / inSampleSize >= targetHeight && halfWidth / inSampleSize >= targetWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun clampDimension(original: Int, max: Int?): Int {
    return if (max != null && original > max) max else original
}
