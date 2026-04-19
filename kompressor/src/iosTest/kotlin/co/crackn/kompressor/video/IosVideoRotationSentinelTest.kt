/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.testutil.Mp4Generator
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

class IosVideoRotationSentinelTest {

    private lateinit var testDir: String
    private val compressor = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-rotation-sentinel-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun exportPath_rotation0_displayedCornersPreserveRgbMarkers() = runTest {
        assertDisplayedCornersPreservedViaExport(rotation = 0)
    }

    @Test
    fun exportPath_rotation90_displayedCornersPreserveRgbMarkers() = runTest {
        assertDisplayedCornersPreservedViaExport(rotation = 90)
    }

    @Test
    fun transcodePath_rotation90_displayedCornersPreserveRgbMarkers() = runTest {
        assertDisplayedCornersPreservedViaTranscode(rotation = 90)
    }

    private suspend fun assertDisplayedCornersPreservedViaExport(rotation: Int) {
        val input = Mp4Generator.generateCornerMarkedMp4(
            outputPath = testDir + "input-export-$rotation.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = rotation,
        )
        val output = testDir + "output-export-$rotation.mp4"
        val result = compressor.compress(inputPath = input, outputPath = output)
        assertTrue(
            result.isSuccess,
            "Export path failed for rotation=$rotation: ${result.exceptionOrNull()}",
        )
        assertDisplayedCorners(output, rotation, pathLabel = "export")
    }

    private suspend fun assertDisplayedCornersPreservedViaTranscode(rotation: Int) {
        val input = Mp4Generator.generateCornerMarkedMp4(
            outputPath = testDir + "input-transcode-$rotation.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = rotation,
        )
        val output = testDir + "output-transcode-$rotation.mp4"
        // Custom bitrate knocks us off the AVAssetExportSession fast path onto AVAssetWriter —
        // the path that PR #56 explicitly patched to forward `preferredTransform`.
        val config = VideoCompressionConfig(videoBitrate = CUSTOM_BITRATE)
        val result = compressor.compress(inputPath = input, outputPath = output, config = config)
        assertTrue(
            result.isSuccess,
            "Transcode path failed for rotation=$rotation: ${result.exceptionOrNull()}",
        )
        assertDisplayedCorners(output, rotation, pathLabel = "transcode")
    }

    private fun assertDisplayedCorners(outputPath: String, rotation: Int, pathLabel: String) {
        val rgba = extractFirstFrameRgba(outputPath)
            ?: fail("Could not extract first frame from $outputPath ($pathLabel, rotation=$rotation)")
        val label = "$pathLabel rotation=$rotation"
        val w = rgba.width
        val h = rgba.height
        assertCornerRgb(rgba, x = 0, y = 0, expected = RED, label = "top-left ($label)")
        assertCornerRgb(rgba, x = w - 1, y = 0, expected = GREEN, label = "top-right ($label)")
        assertCornerRgb(rgba, x = 0, y = h - 1, expected = BLUE, label = "bottom-left ($label)")
    }

    private fun extractFirstFrameRgba(path: String): RgbaBitmap? {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        val generator = AVAssetImageGenerator(asset = asset).apply {
            appliesPreferredTrackTransform = true
            requestedTimeToleranceBefore = CMTimeMake(value = 0, timescale = TIMESCALE)
            requestedTimeToleranceAfter = CMTimeMake(value = 0, timescale = TIMESCALE)
        }
        val cgImage: CGImageRef? = generator.copyCGImageAtTime(
            requestedTime = CMTimeMake(value = 0, timescale = TIMESCALE),
            actualTime = null,
            error = null,
        )
        if (cgImage == null) {
            // Log WARNING and fail the test rather than skipping silently, per CRA-8 DoD.
            NSLog("WARNING VideoRotationSentinel: copyCGImageAtTime returned null for %@", path)
            return null
        }
        return cgImageToRgba(cgImage)
    }

    /**
     * Read the CGImage's raw pixel bytes directly via its [CGImageGetDataProvider] and copy them
     * into an RGBA buffer with B/R swapped. Avoids drawing the CGImage through an intermediate
     * `CGBitmapContext`, which on this pipeline has empirically mirrored the rightmost column's
     * Y coordinate (red/blue on the left edge came through unchanged, but green and black on
     * the right edge swapped top-to-bottom). AVFoundation hands back H.264-decoded CGImages in
     * BGRx byte order (`kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little`), so a direct
     * memcpy with a single channel swap is both simpler and bit-exact.
     */
    private fun cgImageToRgba(cgImage: CGImageRef): RgbaBitmap {
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        val srcBpr = CGImageGetBytesPerRow(cgImage).toInt()
        val provider = CGImageGetDataProvider(cgImage) ?: error("CGImage has no data provider")
        val cfData = CGDataProviderCopyData(provider) ?: error("CGDataProviderCopyData returned null")
        val dstBpr = width * BYTES_PER_PIXEL
        val pixels = UByteArray(dstBpr * height)
        try {
            val len = CFDataGetLength(cfData).toInt()
            val bytePtr = CFDataGetBytePtr(cfData) ?: error("CFDataGetBytePtr returned null")
            val src = bytePtr.readBytes(len)
            for (y in 0 until height) {
                val srcRow = y * srcBpr
                val dstRow = y * dstBpr
                for (x in 0 until width) {
                    val srcOff = srcRow + x * BYTES_PER_PIXEL
                    val dstOff = dstRow + x * BYTES_PER_PIXEL
                    // Source is BGRA (B at byte 0, G at byte 1, R at byte 2, A at byte 3); the
                    // test reads pixels as RGBA so swap B↔R here.
                    pixels[dstOff] = src[srcOff + 2].toUByte()
                    pixels[dstOff + 1] = src[srcOff + 1].toUByte()
                    pixels[dstOff + 2] = src[srcOff].toUByte()
                    pixels[dstOff + 3] = src[srcOff + 3].toUByte()
                }
            }
        } finally {
            CFRelease(cfData)
        }
        return RgbaBitmap(width = width, height = height, pixels = pixels, bytesPerRow = dstBpr)
    }

    private fun assertCornerRgb(bitmap: RgbaBitmap, x: Int, y: Int, expected: IntArray, label: String) {
        val base = y * bitmap.bytesPerRow + x * BYTES_PER_PIXEL
        val r = bitmap.pixels[base].toInt()
        val g = bitmap.pixels[base + 1].toInt()
        val b = bitmap.pixels[base + 2].toInt()
        val dr = abs(r - expected[0])
        val dg = abs(g - expected[1])
        val db = abs(b - expected[2])
        assertTrue(
            dr <= TOLERANCE && dg <= TOLERANCE && db <= TOLERANCE,
            "Pixel at $label ($x,$y) = rgb($r,$g,$b); expected rgb(${expected[0]},${expected[1]},${expected[2]}) ±$TOLERANCE",
        )
    }

    private data class RgbaBitmap(
        val width: Int,
        val height: Int,
        val pixels: UByteArray,
        val bytesPerRow: Int,
    )

    private companion object {
        const val INPUT_WIDTH = 320
        const val INPUT_HEIGHT = 240
        const val INPUT_FRAME_COUNT = 30
        const val CUSTOM_BITRATE = 400_000
        const val TIMESCALE = 30
        // Matches the Android sentinel's tolerance (CRA-8 DoD). Empirically we measure ~3-unit
        // drift per channel through the YUV→H.264→YUV→BGRA pipeline when the fixture writes
        // YUV directly; a wrong-way rotation still produces ~255-unit swaps, so ±5 leaves plenty
        // of diagnostic headroom without false positives from codec quantisation noise.
        const val TOLERANCE = 5
        const val BYTES_PER_PIXEL = 4

        val RED = intArrayOf(255, 0, 0)
        val GREEN = intArrayOf(0, 255, 0)
        val BLUE = intArrayOf(0, 0, 255)
    }
}
