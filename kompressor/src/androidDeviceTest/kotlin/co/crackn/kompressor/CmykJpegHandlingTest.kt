/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.testutil.ByteSearch
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.copyResourceToCache
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Locks in the compressor's behaviour for CMYK JPEG input — the colour space that print /
 * stock-photo pipelines emit and that mobile decoders handle inconsistently across OEMs.
 *
 * Android's `BitmapFactory` is backed by skia which calls into libjpeg-turbo; CMYK support
 * shifted across API levels (older skia builds returned `null`, newer ones convert to RGB
 * using Adobe APP14 information). We don't pin a specific outcome — the public contract is
 * "either the compressor hands back a valid RGB JPEG or a **typed** [ImageCompressionError]".
 *
 * Fixture: `cmyk.jpg` — 32x32 solid-colour JPEG with Adobe APP14 marker and SOF `Nf=4`.
 * Reproducible recipe: `magick -colorspace CMYK`.
 */
class CmykJpegHandlingTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-cmyk-jpeg-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun cmykJpeg_convertsToRgbOrReturnsTypedError() = runTest {
        val input = copyResourceToCache("cmyk.jpg", tempDir)
        assertInputIsCmyk(input.readBytes())

        val output = File(tempDir, "cmyk_out.jpg")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        if (result.isSuccess) {
            // Conversion path — the compressor must emit an RGB (3-component) JPEG. Verifying
            // `Nf == 3` at the SOF marker catches a "wrote the bytes back untouched with the
            // input's 4-component header still intact" regression.
            val bytes = output.readBytes()
            assertTrue(OutputValidators.isValidJpeg(bytes), "Output must be valid JPEG")
            val components = readJpegComponentCount(bytes)
            assertTrue(
                components == JPEG_RGB_COMPONENTS,
                "CMYK input must be re-encoded as RGB (3 components), got $components",
            )
            // Defensive: BitmapFactory should also be able to decode the output.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            assertTrue(opts.outWidth > 0 && opts.outHeight > 0, "Output must be decodable by BitmapFactory")
        } else {
            // Rejection path — error must be one of the typed [ImageCompressionError]
            // subclasses. This anchors the "typed failure" half of the DoD contract: a generic
            // uncaught exception would mean the library's classifyAndroidImageError fell through.
            val err = result.exceptionOrNull()
            assertTrue(
                err is ImageCompressionError,
                "CMYK rejection must be typed, got ${err?.let { it::class.simpleName }}: ${err?.message}",
            )
        }
    }

    private fun assertInputIsCmyk(bytes: ByteArray) {
        // SOF0 (FF C0) / SOF1 (FF C1) / SOF2 (FF C2) header: 2-byte marker, 2-byte length,
        // 1-byte precision, 2-byte height, 2-byte width, 1-byte Nf (4 = CMYK/YCCK).
        val nf = readJpegComponentCount(bytes)
        assertTrue(
            nf == JPEG_CMYK_COMPONENTS,
            "Fixture cmyk.jpg must have Nf=4 (CMYK), got Nf=$nf — did ImageMagick drop the " +
                "-colorspace CMYK flag?",
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
