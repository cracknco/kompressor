@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Creates a test PNG with enough visual entropy that JPEG re-encoding actually
 * reduces the file size — a solid-colour rectangle would compress to a few
 * hundred bytes in PNG (palette-like), which JPEG can't match at any quality,
 * so tests asserting `outputSize < inputSize` would falsely fail.
 *
 * We draw a dense grid of coloured tiles derived from a trigonometric seed so
 * each tile gets a different hue — the result looks noisy but remains
 * deterministic, and no two adjacent tiles share a colour, defeating PNG's
 * run-length advantage while still being JPEG-friendly.
 */
fun createTestImage(testDir: String, width: Int, height: Int): String {
    UIGraphicsBeginImageContextWithOptions(
        CGSizeMake(width.toDouble(), height.toDouble()), true, 1.0,
    )
    try {
        val tileSize = TILE_SIZE_PX
        val cols = (width + tileSize - 1) / tileSize
        val rows = (height + tileSize - 1) / tileSize
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val seed = (row * COLS_PRIME + col).toDouble()
                val hue = (sin(seed) * 0.5 + 0.5)
                val sat = (cos(seed * 2 * PI / HUE_PERIOD) * 0.25 + 0.75)
                val bri = (sin(seed * 2 * PI / BRI_PERIOD) * 0.25 + 0.75)
                UIColor(hue = hue, saturation = sat, brightness = bri, alpha = 1.0).setFill()
                UIRectFill(
                    CGRectMake(
                        (col * tileSize).toDouble(),
                        (row * tileSize).toDouble(),
                        tileSize.toDouble(),
                        tileSize.toDouble(),
                    ),
                )
            }
        }
        val image = UIGraphicsGetImageFromCurrentImageContext()!!
        val data = checkNotNull(UIImagePNGRepresentation(image)) { "PNG encoding failed" }

        val path = testDir + "input_${width}x$height.png"
        val url = NSURL.fileURLWithPath(path)
        check(data.writeToURL(url, atomically = true)) { "Failed to write PNG: $path" }
        return path
    } finally {
        UIGraphicsEndImageContext()
    }
}

// 8x8 tiles give roughly one colour per 64 pixels — dense enough to overwhelm
// PNG's DEFLATE but coarse enough to keep rendering cheap and to stay visually
// structured (not white noise) so JPEG's DCT still compresses well.
private const val TILE_SIZE_PX = 8
private const val COLS_PRIME = 31
private const val HUE_PERIOD = 17.0
private const val BRI_PERIOD = 13.0
