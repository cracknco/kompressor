package co.crackn.kompressor.golden

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImagePresets
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createTestImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Golden tests for image compression. Verify that compression of known inputs
 * produces valid JPEG output with correct dimensions and reasonable file sizes.
 */
class GoldenImageTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-golden-image").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun square1000_defaultConfig_producesValidJpeg() = runTest {
        val input = createTestImage(tempDir, 1000, 1000)
        val output = File(tempDir, "golden_default.jpg")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be valid JPEG")
        assertTrue(compression.outputSize < compression.inputSize, "JPEG should compress PNG input")

        // Verify dimensions unchanged (no resize config)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        assertEquals(1000, options.outWidth, "Width should be preserved")
        assertEquals(1000, options.outHeight, "Height should be preserved")
    }

    @Test
    fun wideImage_thumbnailPreset_resizesCorrectly() = runTest {
        val input = createTestImage(tempDir, 2000, 1000)
        val output = File(tempDir, "golden_thumbnail.jpg")

        val result = compressor.compress(input.absolutePath, output.absolutePath, ImagePresets.THUMBNAIL)

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be valid JPEG")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        // THUMBNAIL preset: maxWidth=320, maxHeight=320, keepAspectRatio=true
        // 2000x1000 → scale by min(320/2000, 320/1000) = min(0.16, 0.32) = 0.16
        // → 320x160
        assertEquals(320, options.outWidth, "Thumbnail width")
        assertEquals(160, options.outHeight, "Thumbnail height (aspect ratio preserved)")
    }

    @Test
    fun wideImage_webPreset_resizesCorrectly() = runTest {
        val input = createTestImage(tempDir, 2000, 1000)
        val output = File(tempDir, "golden_web.jpg")

        val result = compressor.compress(input.absolutePath, output.absolutePath, ImagePresets.WEB)

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be valid JPEG")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        // WEB preset: maxWidth=1920, maxHeight=1920 — 2000x1000 fits maxHeight
        // scale by min(1920/2000, 1920/1000) = min(0.96, 1.92) = 0.96
        // → 1920x960
        assertEquals(1920, options.outWidth, "Web width")
        assertEquals(960, options.outHeight, "Web height (aspect ratio preserved)")
    }

    @Test
    fun outputSizeScalesWithQuality() = runTest {
        val input = createTestImage(tempDir, 1000, 1000)
        val outputLow = File(tempDir, "golden_q10.jpg")
        val outputHigh = File(tempDir, "golden_q95.jpg")

        compressor.compress(input.absolutePath, outputLow.absolutePath, ImageCompressionConfig(quality = 10))
        compressor.compress(input.absolutePath, outputHigh.absolutePath, ImageCompressionConfig(quality = 95))

        assertTrue(OutputValidators.isValidJpeg(outputLow.readBytes()))
        assertTrue(OutputValidators.isValidJpeg(outputHigh.readBytes()))
        assertTrue(
            outputLow.length() < outputHigh.length(),
            "q10 (${outputLow.length()}) should be smaller than q95 (${outputHigh.length()})",
        )
    }
}
