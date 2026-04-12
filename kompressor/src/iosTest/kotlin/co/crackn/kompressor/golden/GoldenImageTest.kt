@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.golden

import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImagePresets
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for iOS image compression. Verify that compression of known inputs
 * produces valid JPEG output with correct dimensions and reasonable file sizes.
 */
class GoldenImageTest {

    private lateinit var testDir: String
    private val compressor = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-golden-image-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun square1000_defaultConfig_producesValidJpeg() = runTest {
        val inputPath = createTestImage(testDir, 1000, 1000)
        val outputPath = testDir + "golden_default.jpg"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidJpeg(readBytes(outputPath)), "Output must be valid JPEG")
        val compression = result.getOrThrow()
        assertTrue(
            compression.outputSize < compression.inputSize,
            "JPEG must beat PNG on the continuous-tone fixture: " +
                "output=${compression.outputSize}, input=${compression.inputSize}",
        )

        val outputImage = UIImage(contentsOfFile = outputPath)
        val cgImage = outputImage.CGImage!!
        assertEquals(1000, CGImageGetWidth(cgImage).toInt(), "Width preserved")
        assertEquals(1000, CGImageGetHeight(cgImage).toInt(), "Height preserved")
    }

    @Test
    fun wideImage_thumbnailPreset_resizesCorrectly() = runTest {
        val inputPath = createTestImage(testDir, 2000, 1000)
        val outputPath = testDir + "golden_thumbnail.jpg"

        val result = compressor.compress(inputPath, outputPath, ImagePresets.THUMBNAIL)

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidJpeg(readBytes(outputPath)), "Output must be valid JPEG")

        val outputImage = UIImage(contentsOfFile = outputPath)
        val cgImage = outputImage.CGImage!!
        assertEquals(320, CGImageGetWidth(cgImage).toInt(), "Thumbnail width")
        assertEquals(160, CGImageGetHeight(cgImage).toInt(), "Thumbnail height (aspect preserved)")
    }

    @Test
    fun wideImage_webPreset_resizesCorrectly() = runTest {
        val inputPath = createTestImage(testDir, 2000, 1000)
        val outputPath = testDir + "golden_web.jpg"

        val result = compressor.compress(inputPath, outputPath, ImagePresets.WEB)

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidJpeg(readBytes(outputPath)), "Output must be valid JPEG")

        val outputImage = UIImage(contentsOfFile = outputPath)
        val cgImage = outputImage.CGImage!!
        assertEquals(1920, CGImageGetWidth(cgImage).toInt(), "Web width")
        assertEquals(960, CGImageGetHeight(cgImage).toInt(), "Web height (aspect preserved)")
    }

    @Test
    fun outputSizeScalesWithQuality() = runTest {
        val inputPath = createTestImage(testDir, 1000, 1000)
        val outputLow = testDir + "golden_q10.jpg"
        val outputHigh = testDir + "golden_q95.jpg"

        compressor.compress(inputPath, outputLow, ImageCompressionConfig(quality = 10))
        compressor.compress(inputPath, outputHigh, ImageCompressionConfig(quality = 95))

        assertTrue(OutputValidators.isValidJpeg(readBytes(outputLow)))
        assertTrue(OutputValidators.isValidJpeg(readBytes(outputHigh)))

        val sizeLow = fileSize(outputLow)
        val sizeHigh = fileSize(outputHigh)
        assertTrue(sizeLow < sizeHigh, "q10 ($sizeLow) should be smaller than q95 ($sizeHigh)")
    }

}
