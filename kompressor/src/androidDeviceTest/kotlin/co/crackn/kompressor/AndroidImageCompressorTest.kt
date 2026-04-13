package co.crackn.kompressor

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createTestImage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
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
        val input = createTestImage(tempDir, 1000, 1000)
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
        assertTrue(compression.durationMs >= 0)
        assertTrue(
            compression.compressionRatio < 1f,
            "JPEG should compress PNG input: ratio=${compression.compressionRatio}",
        )
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output should be valid JPEG")
    }

    @Test
    fun exifOrientation_rotatedInput_outputRespectsRotation() = runTest {
        // Regression gate for `ExifInterface.TAG_ORIENTATION` handling in
        // `AndroidImageCompressor`. Input is a 200×100 JPEG tagged with Orientation=6
        // (ROTATE_90 CW); the compressor must swap dimensions so the output is 100×200.
        // Without this test the full EXIF-rotation branch in `AndroidImageCompressor`
        // (lines 49-59) had no device-side gate.
        val input = co.crackn.kompressor.testutil.createExifRotatedJpeg(
            tempDir, width = 200, height = 100,
            exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90,
        )
        val output = java.io.File(tempDir, "rotated_out.jpg")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(output.absolutePath, options)
        assertEquals(100, options.outWidth, "EXIF ROTATE_90: output width must be input height")
        assertEquals(200, options.outHeight, "EXIF ROTATE_90: output height must be input width")
    }

    @Test
    fun compressImage_withResize_reducesDimensions() = runTest {
        val input = createTestImage(tempDir, 2000, 1000)
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
        val input = createTestImage(tempDir, 1000, 1000)
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

}
