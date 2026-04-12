package co.crackn.kompressor.testutil

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin

/**
 * Creates a PNG fixture whose content is representative of real-world photographic input:
 * a low-frequency multichannel sine-wave gradient filling every pixel with a subtly different
 * value. No two neighbouring pixels share a colour, but transitions between them are smooth.
 *
 * Why this matters for image tests: a palette-friendly fixture (solid colour, coarse tile
 * grid, flat regions) lets PNG's DEFLATE + filter predictors beat JPEG's per-block DCT
 * overhead, breaking any `outputSize < inputSize` assertion. A continuous-tone fixture inverts
 * the bias: JPEG's DCT captures the low-frequency structure in a few quantised coefficients
 * per block while PNG has to store a near-random byte stream that DEFLATE can't compress well.
 *
 * Concretely, at 1000×1000:
 *  * PNG (uncompressed: 4 MB) lands around 600 KB – 1.5 MB after DEFLATE.
 *  * JPEG quality 85 lands around 40 – 120 KB.
 *
 * The fixture is deterministic — identical bytes across runs — so golden tests remain stable.
 */
fun createTestImage(tempDir: File, width: Int, height: Int): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    // PRNG per-pixel noise layered on top of the gradient — the gradient alone is *too*
    // compressible (a smooth analytic surface DEFLATE handles decently), so we add ±12 of
    // deterministic LCG noise per channel to approximate sensor-noise from a real camera.
    // That pushes PNG output into the 700 KB – 1.5 MB range while keeping JPEG around
    // 40–120 KB for 1000×1000, making the `outputSize < inputSize` assertion robust.
    var prng = PRNG_SEED
    for (y in 0 until height) {
        for (x in 0 until width) {
            prng = prng * PRNG_MULT + PRNG_INC
            val noiseR = (((prng ushr PRNG_SHIFT_R) and PRNG_NOISE_MASK).toInt() - PRNG_NOISE_BIAS)
            val noiseG = (((prng ushr PRNG_SHIFT_G) and PRNG_NOISE_MASK).toInt() - PRNG_NOISE_BIAS)
            val noiseB = (((prng ushr PRNG_SHIFT_B) and PRNG_NOISE_MASK).toInt() - PRNG_NOISE_BIAS)
            val r = (gradientChannel(x, y, SCALE_R_X, SCALE_R_Y, PHASE_R) + noiseR).coerceIn(0, MAX_BYTE)
            val g = (gradientChannel(x, y, SCALE_G_X, SCALE_G_Y, PHASE_G) + noiseG).coerceIn(0, MAX_BYTE)
            val b = (gradientChannel(x, y, SCALE_B_X, SCALE_B_Y, PHASE_B) + noiseB).coerceIn(0, MAX_BYTE)
            pixels[y * width + x] = Color.argb(ALPHA_OPAQUE, r, g, b)
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    val file = File(tempDir, "input_${width}x$height.png")
    try {
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
        }
    } finally {
        bitmap.recycle()
    }
    return file
}

/** One RGB channel of the continuous-tone gradient, in the byte range [0, 255]. */
private fun gradientChannel(x: Int, y: Int, xScale: Double, yScale: Double, phase: Double): Int {
    val v = CENTER + AMPLITUDE * sin(x / xScale + y / yScale + phase)
    return v.toInt().coerceIn(0, MAX_BYTE)
}

private const val CENTER = 128.0
private const val AMPLITUDE = 96.0
private const val MAX_BYTE = 255
private const val ALPHA_OPAQUE = 255
private const val PNG_QUALITY = 100

// Three channels at distinct spatial frequencies + phase offsets ⇒ ~1 M unique colours across
// a 1000×1000 image, no palette-compressible regions. Frequencies chosen low enough that JPEG's
// 8×8 DCT captures them efficiently (so the test actually validates "the compressor compressed").
private const val SCALE_R_X = 41.0
private const val SCALE_R_Y = 53.0
private const val SCALE_G_X = 37.0
private const val SCALE_G_Y = 47.0
private const val SCALE_B_X = 43.0
private const val SCALE_B_Y = 59.0
private const val PHASE_R = 0.0
private const val PHASE_G = 1.7
private const val PHASE_B = 3.2

// Deterministic LCG noise — Numerical Recipes constants. Gives each channel ±12 of variance
// around the gradient baseline, approximating camera sensor noise.
private const val PRNG_SEED = 2_654_435_761L
private const val PRNG_MULT = 1_664_525L
private const val PRNG_INC = 1_013_904_223L
private const val PRNG_SHIFT_R = 16
private const val PRNG_SHIFT_G = 20
private const val PRNG_SHIFT_B = 24
private const val PRNG_NOISE_MASK = 0x1FL // 5 bits → 0..31 → shifted to -12..+19 via bias
private const val PRNG_NOISE_BIAS = 12
