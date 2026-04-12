package co.crackn.kompressor.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Creates a test PNG with enough visual entropy that JPEG re-encoding actually
 * reduces file size — a solid-colour fixture would compress to a few hundred
 * bytes in PNG (palette + RLE), which JPEG cannot match at any quality.
 *
 * We draw a grid of coloured tiles derived from a trigonometric seed so each
 * tile gets a different hue. The result is visually structured (not white
 * noise) so JPEG's DCT still compresses well, while defeating PNG's
 * run-length advantage on flat colour regions.
 */
fun createTestImage(tempDir: File, width: Int, height: Int): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    val tileSize = TILE_SIZE_PX
    val cols = (width + tileSize - 1) / tileSize
    val rows = (height + tileSize - 1) / tileSize
    val hsv = FloatArray(3)
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val seed = (row * COLS_PRIME + col).toDouble()
            // Hue in [0, 360), saturation + brightness in [0.5, 1.0]
            hsv[0] = ((sin(seed) * 0.5 + 0.5) * HUE_RANGE).toFloat()
            hsv[1] = (cos(seed * 2 * PI / SAT_PERIOD) * 0.25 + 0.75).toFloat()
            hsv[2] = (sin(seed * 2 * PI / VAL_PERIOD) * 0.25 + 0.75).toFloat()
            paint.color = Color.HSVToColor(hsv)
            canvas.drawRect(
                (col * tileSize).toFloat(),
                (row * tileSize).toFloat(),
                ((col + 1) * tileSize).toFloat(),
                ((row + 1) * tileSize).toFloat(),
                paint,
            )
        }
    }

    val file = File(tempDir, "input_${width}x$height.png")
    try {
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encoding failed" }
        }
    } finally {
        bitmap.recycle()
    }
    return file
}

// 8x8 tiles give ~one colour per 64 pixels — dense enough to overwhelm PNG's
// DEFLATE but coarse enough to keep rendering cheap and to stay visually
// structured (not white noise) so JPEG's DCT still compresses well.
private const val TILE_SIZE_PX = 8
private const val COLS_PRIME = 31
private const val HUE_RANGE = 360.0
private const val SAT_PERIOD = 17.0
private const val VAL_PERIOD = 13.0
