@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGImageAlphaInfo
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import kotlin.math.sin

/**
 * Mirror of the Android `createTestImage` helper — produces a continuous-tone photographic-like
 * PNG where every pixel is a subtly different value derived from a low-frequency multichannel
 * sine gradient. See the Android file for the full rationale; summary: a palette-friendly
 * fixture lets PNG beat JPEG and breaks `outputSize < inputSize` assertions, so we generate
 * data that JPEG's DCT handles efficiently while PNG's DEFLATE cannot compress.
 *
 * At 1000×1000 expect PNG ≈ 700 KB – 1.5 MB, JPEG q85 ≈ 40 – 120 KB.
 */
fun createTestImage(testDir: String, width: Int, height: Int): String {
    val byteCount = width * height * BYTES_PER_PIXEL
    val pixels = UByteArray(byteCount)
    fillGradientPixels(pixels, width, height)

    pixels.usePinned { pinned ->
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0) as kotlinx.cinterop.CValuesRef<UByteVar>,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = BITS_PER_COMPONENT,
            bytesPerRow = (width * BYTES_PER_PIXEL).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        )
        val cgImage = checkNotNull(CGBitmapContextCreateImage(ctx)) { "CGBitmapContext failed" }
        val uiImage = UIImage(cGImage = cgImage)
        val data = checkNotNull(UIImagePNGRepresentation(uiImage)) { "PNG encoding failed" }

        val path = testDir + "input_${width}x$height.png"
        val url = NSURL.fileURLWithPath(path)
        check(data.writeToURL(url, atomically = true)) { "Failed to write PNG: $path" }
        return path
    }
}

/**
 * Fill [dst] with an RGBA8888 continuous-tone gradient — three sine waves at distinct spatial
 * frequencies, one per colour channel. Produces ~1 M unique colours across a 1000×1000 image,
 * no palette-compressible regions.
 */
private fun fillGradientPixels(dst: UByteArray, width: Int, height: Int) {
    var idx = 0
    var prng = PRNG_SEED
    for (y in 0 until height) {
        for (x in 0 until width) {
            prng = prng * PRNG_MULT + PRNG_INC
            val noiseR = (((prng ushr PRNG_SHIFT_R) and PRNG_NOISE_MASK).toInt() - PRNG_NOISE_BIAS)
            val noiseG = (((prng ushr PRNG_SHIFT_G) and PRNG_NOISE_MASK).toInt() - PRNG_NOISE_BIAS)
            val noiseB = (((prng ushr PRNG_SHIFT_B) and PRNG_NOISE_MASK).toInt() - PRNG_NOISE_BIAS)
            dst[idx++] = gradientWithNoise(x, y, SCALE_R_X, SCALE_R_Y, PHASE_R, noiseR)
            dst[idx++] = gradientWithNoise(x, y, SCALE_G_X, SCALE_G_Y, PHASE_G, noiseG)
            dst[idx++] = gradientWithNoise(x, y, SCALE_B_X, SCALE_B_Y, PHASE_B, noiseB)
            dst[idx++] = ALPHA_OPAQUE
        }
    }
}

/** Continuous-tone gradient + per-pixel noise — see the Android file for the rationale. */
private fun gradientWithNoise(
    x: Int,
    y: Int,
    xScale: Double,
    yScale: Double,
    phase: Double,
    noise: Int,
): UByte {
    val v = CENTER + AMPLITUDE * sin(x / xScale + y / yScale + phase) + noise
    return v.toInt().coerceIn(0, MAX_BYTE).toUByte()
}

private const val BYTES_PER_PIXEL = 4
private const val BITS_PER_COMPONENT: ULong = 8u
private const val CENTER = 128.0
private const val AMPLITUDE = 96.0
private const val MAX_BYTE = 255
private val ALPHA_OPAQUE: UByte = 255u

private const val SCALE_R_X = 41.0
private const val SCALE_R_Y = 53.0
private const val SCALE_G_X = 37.0
private const val SCALE_G_Y = 47.0
private const val SCALE_B_X = 43.0
private const val SCALE_B_Y = 59.0
private const val PHASE_R = 0.0
private const val PHASE_G = 1.7
private const val PHASE_B = 3.2

private const val PRNG_SEED = 2_654_435_761L
private const val PRNG_MULT = 1_664_525L
private const val PRNG_INC = 1_013_904_223L
private const val PRNG_SHIFT_R = 16
private const val PRNG_SHIFT_G = 20
private const val PRNG_SHIFT_B = 24
private const val PRNG_NOISE_MASK = 0x1FL
private const val PRNG_NOISE_BIAS = 12
