/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.ByteSearch
import co.crackn.kompressor.testutil.CmykJpegFixture
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS mirror of `androidDeviceTest/.../CmykJpegHandlingTest.kt`. Locks in the contract that a
 * CMYK JPEG is either transparently re-encoded as RGB or rejected with a typed
 * [ImageCompressionError].
 *
 * iOS decodes CMYK JPEGs via ColorSync (the Adobe APP14 marker carries the colour-transform
 * hint). The `UIImage → UIGraphicsBeginImageContextWithOptions(opaque=true) → drawInRect →
 * UIImageJPEGRepresentation` pipeline always produces an RGB bitmap, so in practice the success
 * branch is the expected path. The failure branch remains in the test for symmetry with Android
 * and to guard against a future iOS SDK regression.
 *
 * Fixture bytes are inlined via [CmykJpegFixture] rather than loaded from a bundle resource —
 * see that file's docstring for why.
 */
class CmykJpegHandlingTest {

    private lateinit var testDir: String
    private val compressor = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-cmyk-jpeg-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun cmykJpeg_convertsToRgbOrReturnsTypedError() = runTest {
        val inputPath = testDir + "cmyk.jpg"
        writeBytes(inputPath, CmykJpegFixture.BYTES)
        assertInputIsCmyk(CmykJpegFixture.BYTES)

        val outputPath = testDir + "cmyk_out.jpg"

        val result = compressor.compress(inputPath, outputPath)

        if (result.isSuccess) {
            // Conversion path — the compressor must emit a valid RGB (3-component) JPEG.
            // Validating the SOI/EOI markers first produces a clear diagnostic when the
            // compressor wrote 0 bytes or non-JPEG garbage — mirrors the Android sibling's
            // `OutputValidators.isValidJpeg` gate.
            val outputBytes = readBytes(outputPath)
            assertTrue(OutputValidators.isValidJpeg(outputBytes), "Output must be valid JPEG")
            // Verifying `Nf == 3` at the SOF marker catches a "wrote the bytes back untouched
            // with the input's 4-component header still intact" regression.
            val components = readJpegComponentCount(outputBytes)
            assertTrue(
                components == JPEG_RGB_COMPONENTS,
                "CMYK input must be re-encoded as RGB (3 components), got $components",
            )
        } else {
            // Rejection path — error must be a typed [ImageCompressionError] subclass. This
            // anchors the "typed failure" half of the DoD contract: a generic uncaught exception
            // would mean iOS decoding/encoding raised something the compressor failed to
            // classify.
            val err = result.exceptionOrNull()
            assertNotNull(err)
            assertTrue(
                err is ImageCompressionError,
                "CMYK rejection must be typed, got ${err::class.simpleName}: ${err.message}",
            )
        }
    }

    private fun assertInputIsCmyk(bytes: ByteArray) {
        val nf = readJpegComponentCount(bytes)
        assertTrue(
            nf == JPEG_CMYK_COMPONENTS,
            "Fixture cmyk.jpg must have Nf=4 (CMYK), got Nf=$nf — did the fixture lose the " +
                "-colorspace CMYK flag during regeneration?",
        )
    }

    private fun readJpegComponentCount(bytes: ByteArray): Int {
        val sofIndex = ByteSearch.indexOfAny(
            bytes,
            byteArrayOf(0xFF.toByte(), 0xC0.toByte()),
            byteArrayOf(0xFF.toByte(), 0xC1.toByte()),
            byteArrayOf(0xFF.toByte(), 0xC2.toByte()),
        )
        if (sofIndex < 0 || sofIndex + SOF_NF_OFFSET >= bytes.size) return -1
        return bytes[sofIndex + SOF_NF_OFFSET].toInt() and 0xFF
    }

    private companion object {
        const val JPEG_CMYK_COMPONENTS = 4
        const val JPEG_RGB_COMPONENTS = 3

        // Nf byte lives at SOF+9: marker(2) + length(2) + precision(1) + height(2) + width(2)
        const val SOF_NF_OFFSET = 9
    }
}
