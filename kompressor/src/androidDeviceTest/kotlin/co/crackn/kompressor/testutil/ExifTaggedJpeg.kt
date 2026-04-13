package co.crackn.kompressor.testutil

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Write a JPEG fixture whose EXIF `Orientation` tag signals the compressor needs to rotate
 * before encoding. The input bitmap is a [width] × [height] photographic gradient (continuous-
 * tone so JPEG compresses it efficiently); the EXIF header is appended post-encode via
 * [ExifInterface.setAttribute].
 *
 * Used by `AndroidImageCompressorTest.exifOrientation_rotatedInput_outputRespectsRotation` to
 * verify the compressor's rotation pipeline end-to-end — a dimension of `width × height`
 * together with `orientation=6` (ROTATE_90) must produce an output of `height × width`.
 */
fun createExifRotatedJpeg(
    tempDir: File,
    width: Int,
    height: Int,
    exifOrientation: Int,
): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            // Simple continuous-tone gradient so JPEG can actually encode it.
            val r = (COMPONENT_MID + (COMPONENT_AMPLITUDE * x / width)).coerceIn(0, MAX_BYTE)
            val g = (COMPONENT_MID + (COMPONENT_AMPLITUDE * y / height)).coerceIn(0, MAX_BYTE)
            val b = (COMPONENT_MID + ((COMPONENT_AMPLITUDE * (x + y)) / (width + height)))
                .coerceIn(0, MAX_BYTE)
            pixels[y * width + x] = Color.argb(ALPHA_OPAQUE, r, g, b)
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    val file = File(tempDir, "exif_${exifOrientation}_${width}x$height.jpg")
    try {
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
        }
    } finally {
        bitmap.recycle()
    }
    // Append the EXIF orientation tag. `ExifInterface` rewrites the file in place.
    val exif = ExifInterface(file.absolutePath)
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
    exif.saveAttributes()
    return file
}

private const val ALPHA_OPAQUE = 255
private const val MAX_BYTE = 255
private const val COMPONENT_MID = 64
private const val COMPONENT_AMPLITUDE = 191
private const val JPEG_QUALITY = 90
