package co.crackn.kompressor

import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
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

@OptIn(ExperimentalForeignApi::class)
class IosImageCompressorTest {

    private lateinit var testDir: String
    private val compressor = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressImage_producesValidOutput() = runTest {
        val inputPath = createTestImage(testDir, 1000, 1000)
        val outputPath = testDir + "output.jpg"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        // No `compressionRatio < 1` here — that's not a universal invariant on synthetic
        // fixtures. See the matching note in GoldenImageTest.square1000_defaultConfig_*.
        val outputBytes = readBytes(outputPath)
        assertTrue(OutputValidators.isValidJpeg(outputBytes), "Output should be valid JPEG")
    }

    @Test
    fun compressImage_withResize_reducesDimensions() = runTest {
        val inputPath = createTestImage(testDir, 2000, 1000)
        val outputPath = testDir + "resized.jpg"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = ImageCompressionConfig(maxWidth = 500, maxHeight = 500),
        )

        assertTrue(result.isSuccess)
        val outputImage = UIImage(contentsOfFile = outputPath)
        val cgImage = outputImage.CGImage!!
        assertEquals(500, CGImageGetWidth(cgImage).toInt())
        assertEquals(250, CGImageGetHeight(cgImage).toInt())
    }

    @Test
    fun compressImage_noResize() = runTest {
        val inputPath = createTestImage(testDir, 500, 500)
        val outputPath = testDir + "no_resize.jpg"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = ImageCompressionConfig(maxWidth = 500, maxHeight = 500),
        )

        assertTrue(result.isSuccess)
        val outputImage = UIImage(contentsOfFile = outputPath)
        val cgImage = outputImage.CGImage!!
        assertEquals(500, CGImageGetWidth(cgImage).toInt())
        assertEquals(500, CGImageGetHeight(cgImage).toInt())
    }

    @Test
    fun compressImage_qualityAffectsSize() = runTest {
        val inputPath = createTestImage(testDir, 1000, 1000)
        val outputLow = testDir + "low.jpg"
        val outputMid = testDir + "mid.jpg"
        val outputHigh = testDir + "high.jpg"

        compressor.compress(inputPath, outputLow, ImageCompressionConfig(quality = 10))
        compressor.compress(inputPath, outputMid, ImageCompressionConfig(quality = 50))
        compressor.compress(inputPath, outputHigh, ImageCompressionConfig(quality = 95))

        val sizeLow = fileSize(outputLow)
        val sizeMid = fileSize(outputMid)
        val sizeHigh = fileSize(outputHigh)
        assertTrue(sizeLow < sizeMid, "q10($sizeLow) should be < q50($sizeMid)")
        assertTrue(sizeMid < sizeHigh, "q50($sizeMid) should be < q95($sizeHigh)")
    }

    @Test
    fun compressImage_fileNotFound_returnsFailure() = runTest {
        val result = compressor.compress("/nonexistent/image.png", testDir + "out.jpg")
        assertTrue(result.isFailure)
    }

}