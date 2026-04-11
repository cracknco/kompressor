package co.crackn.kompressor.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

/** Creates a test PNG image with a blue background and a red rectangle in the top-left quarter. */
fun createTestImage(tempDir: File, width: Int, height: Int): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.BLUE)
    val paint = Paint().apply { color = Color.RED }
    canvas.drawRect(0f, 0f, width / 2f, height / 2f, paint)

    val file = File(tempDir, "input_${width}x$height.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
}
