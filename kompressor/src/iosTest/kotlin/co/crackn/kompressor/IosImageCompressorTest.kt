package co.crackn.kompressor

import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToURL
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill
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
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressImage_producesValidOutput() = runTest {
        val inputPath = createTestImage(1000, 1000)
        val outputPath = testDir + "output.jpg"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.compressionRatio < 1f)
        assertTrue(compression.durationMs >= 0)
    }

    @Test
    fun compressImage_withResize_reducesDimensions() = runTest {
        val inputPath = createTestImage(2000, 1000)
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
    fun compressImage_qualityAffectsSize() = runTest {
        val inputPath = createTestImage(1000, 1000)
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

    @Test
    fun compressImage_progressReported() = runTest {
        val inputPath = createTestImage(1000, 1000)
        val outputPath = testDir + "progress.jpg"
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first())
        assertEquals(1f, progressValues.last())
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    private fun createTestImage(width: Int, height: Int): String {
        UIGraphicsBeginImageContextWithOptions(
            CGSizeMake(width.toDouble(), height.toDouble()), true, 1.0,
        )
        UIColor.blueColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        UIColor.redColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, width / 2.0, height / 2.0))
        val image = UIGraphicsGetImageFromCurrentImageContext()!!
        UIGraphicsEndImageContext()

        val path = testDir + "input_${width}x$height.png"
        val data = UIImagePNGRepresentation(image)!!
        val url = platform.Foundation.NSURL.fileURLWithPath(path)
        data.writeToURL(url, atomically = true)
        return path
    }

    private fun fileSize(path: String): Long {
        val attrs = platform.Foundation.NSFileManager.defaultManager
            .attributesOfItemAtPath(path, null) ?: error("File not found: $path")
        return (attrs[platform.Foundation.NSFileSize] as? Number)?.toLong() ?: 0L
    }
}
