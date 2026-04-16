/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Test-only JPEG fixture used to verify [co.crackn.kompressor.image.AndroidImageCompressor]'s
 * EXIF-orientation handling.
 *
 * The bitmap is filled with four distinct solid-colour quadrants (top-left red, top-right blue,
 * bottom-left green, bottom-right yellow) so a caller can assert not just the expected output
 * dimensions but **which pixel ends up where** after rotation — guarding against the trivial
 * "swap metadata, skip actual rotation" regression.
 *
 * Call sites provide the EXIF `Orientation` tag they want; `ExifInterface` rewrites the file
 * in place after the JPEG is encoded.
 */
fun createExifTaggedJpeg(
    tempDir: File,
    width: Int,
    height: Int,
    exifOrientation: Int,
): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    val halfW = width / 2
    val halfH = height / 2
    for (y in 0 until height) {
        for (x in 0 until width) {
            val left = x < halfW
            val top = y < halfH
            pixels[y * width + x] = when {
                left && top -> TOP_LEFT_COLOR
                !left && top -> TOP_RIGHT_COLOR
                left && !top -> BOTTOM_LEFT_COLOR
                else -> BOTTOM_RIGHT_COLOR
            }
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
    val exif = ExifInterface(file.absolutePath)
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
    exif.saveAttributes()
    return file
}

// Quadrant sentinel colours a test caller can identify in the rotated output to prove the
// pixels actually moved. The RGB component literals are auto-documented by the trailing
// colour name — `@Suppress("MagicNumber")` applied uniformly to all four for consistency.
@Suppress("MagicNumber")
val TOP_LEFT_COLOR: Int = Color.rgb(255, 0, 0) // red

@Suppress("MagicNumber")
val TOP_RIGHT_COLOR: Int = Color.rgb(0, 0, 255) // blue

@Suppress("MagicNumber")
val BOTTOM_LEFT_COLOR: Int = Color.rgb(0, 255, 0) // green

@Suppress("MagicNumber")
val BOTTOM_RIGHT_COLOR: Int = Color.rgb(255, 255, 0) // yellow

private const val JPEG_QUALITY = 95
