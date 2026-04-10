package co.crackn.kompressor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidImageCompressorTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressImage_producesValidOutput() = runTest {
        val input = createTestImage(1000, 1000)
        val output = File(tempDir, "output.jpg")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
        )

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(output.exists())
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.compressionRatio < 1f)
        assertTrue(compression.durationMs >= 0)
    }

    @Test
    fun compressImage_withResize_reducesDimensions() = runTest {
        val input = createTestImage(2000, 1000)
        val output = File(tempDir, "resized.jpg")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = ImageCompressionConfig(maxWidth = 500, maxHeight = 500),
        )

        assertTrue(result.isSuccess)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        assertEquals(500, options.outWidth)
        assertEquals(250, options.outHeight)
    }

    @Test
    fun compressImage_qualityAffectsSize() = runTest {
        val input = createTestImage(1000, 1000)
        val outputLow = File(tempDir, "low.jpg")
        val outputMid = File(tempDir, "mid.jpg")
        val outputHigh = File(tempDir, "high.jpg")

        compressor.compress(input.absolutePath, outputLow.absolutePath, ImageCompressionConfig(quality = 10))
        compressor.compress(input.absolutePath, outputMid.absolutePath, ImageCompressionConfig(quality = 50))
        compressor.compress(input.absolutePath, outputHigh.absolutePath, ImageCompressionConfig(quality = 95))

        assertTrue(outputLow.length() < outputMid.length())
        assertTrue(outputMid.length() < outputHigh.length())
    }

    @Test
    fun compressImage_fileNotFound_returnsFailure() = runTest {
        val output = File(tempDir, "out.jpg")
        val result = compressor.compress("/nonexistent/image.png", output.absolutePath)
        assertTrue(result.isFailure)
    }

    @Test
    fun compressImage_progressReported() = runTest {
        val input = createTestImage(1000, 1000)
        val output = File(tempDir, "progress.jpg")
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first())
        assertEquals(1f, progressValues.last())
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    private fun createTestImage(width: Int, height: Int): File {
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
}
