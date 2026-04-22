/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.MinimalPngFixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Input-robustness sweep for the Android image compressor. Exercises edge-case PNG bit depths,
 * palette-indexed PNGs, interlaced (Adam7) PNGs, truncated JPEGs, and corrupt-EXIF JPEGs. CMYK
 * JPEG coverage is deferred to a later fixture-prep PR because ImageMagick / a CMYK encoder
 * aren't available on-device.
 */
class ImageInputRobustnessTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-image-robustness-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun sixteenBitPerChannelPng_compressesToValidJpeg() = runTest {
        val input = File(tempDir, "rgba16.png").apply { writeBytes(MinimalPngFixtures.rgba16bit2x2()) }
        val output = File(tempDir, "rgba16_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess, "16bpc PNG compression failed: ${result.exceptionOrNull()}")
        assertTrue(output.length() > 0, "Output must be non-empty")
    }

    @Test
    fun indexedPalettePng_compressesToValidJpeg() = runTest {
        val input = File(tempDir, "indexed.png").apply { writeBytes(MinimalPngFixtures.indexed4x4()) }
        val output = File(tempDir, "indexed_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess, "Indexed PNG compression failed: ${result.exceptionOrNull()}")
        assertTrue(output.length() > 0)
    }

    @Test
    fun interlacedAdam7Png_handlesGracefully() = runTest {
        // Adam7-interlaced PNGs are rare in the wild and Android `BitmapFactory` historically
        // has limited support for hand-crafted minimal Adam7 fixtures (it accepts encoder-
        // produced ones from `Bitmap.compress` but rejects some bit-perfect-spec variants).
        // The robustness contract is: the compressor must NOT crash and must surface a typed
        // result either way. Accepting both branches reflects real platform variance — Pixel 6
        // may accept, older / different OEMs may reject.
        val input = File(tempDir, "interlaced.png").apply {
            writeBytes(MinimalPngFixtures.rgbaInterlaced4x4())
        }
        val output = File(tempDir, "interlaced_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        if (result.isSuccess) {
            assertTrue(output.length() > 0, "Successful compression must produce a non-empty file")
        } else {
            val err = result.exceptionOrNull()
            assertTrue(
                err is ImageCompressionError.DecodingFailed,
                "Decode rejection must surface as typed DecodingFailed, got " +
                    "${err?.let { it::class.simpleName }}: ${err?.message}",
            )
        }
    }

    @Test
    fun truncatedJpeg_failsWithDecodingFailed() = runTest {
        val input = File(tempDir, "truncated.jpg").apply {
            writeBytes(MinimalPngFixtures.TRUNCATED_JPEG)
        }
        val output = File(tempDir, "truncated_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isFailure, "Truncated JPEG must fail")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.DecodingFailed,
            "Expected DecodingFailed for truncated JPEG, got ${err::class.simpleName}: ${err.message}",
        )
    }

    @Test
    fun corruptExifPayload_stillCompressesSuccessfully() = runTest {
        // Produce a real JPEG, then overwrite its APP1 (EXIF) payload with garbage. The image
        // compressor must fall back to 0° rotation rather than propagating the EXIF parse error.
        val valid = createValidJpeg()
        val corrupted = MinimalPngFixtures.corruptExifPayload(valid)
        val input = File(tempDir, "corrupt_exif.jpg").apply { writeBytes(corrupted) }
        val output = File(tempDir, "corrupt_exif_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(
            result.isSuccess,
            "Corrupt EXIF must not break compression: ${result.exceptionOrNull()}",
        )
        assertEquals(true, output.length() > 0)
    }

    private fun createValidJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(TEST_WIDTH, TEST_HEIGHT, Bitmap.Config.ARGB_8888)
        val file = File(tempDir, "_valid_src.jpg")
        try {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
            }
        } finally {
            bitmap.recycle()
        }
        val bytes = file.readBytes()
        file.delete()
        // Inject a minimal APP1 (EXIF) segment right after SOI if the encoder didn't emit one,
        // so `corruptExifPayload` has something to corrupt. Android's JPEG encoder normally
        // writes only APP0 (JFIF); we prepend an APP1 stub whose payload we can garble.
        return if (hasApp1(bytes)) bytes else injectApp1Stub(bytes)
    }

    private fun hasApp1(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xE1.toByte()) return true
            i++
        }
        return false
    }

    private fun injectApp1Stub(bytes: ByteArray): ByteArray {
        // Build a valid APP1 segment: marker FF E1 + 2-byte big-endian length + payload of
        // length-2 bytes. Length = 32 (2 header + 30 payload).
        val app1 = ByteArray(APP1_TOTAL_SIZE).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xE1.toByte()
            this[2] = 0x00
            this[3] = (APP1_TOTAL_SIZE - 2).toByte()
            // Payload: "Exif\0\0" + 24 bytes of placeholder data that `corruptExifPayload` will
            // overwrite. Content doesn't matter since the compressor only reads orientation.
            val header = "Exif\u0000\u0000".encodeToByteArray()
            header.copyInto(this, 4)
        }
        // Insert after SOI (bytes 0..1 = FF D8). Result: FF D8 + app1 + rest.
        val out = ByteArray(bytes.size + app1.size)
        bytes.copyInto(out, 0, 0, 2)
        app1.copyInto(out, 2)
        bytes.copyInto(out, 2 + app1.size, 2)
        return out
    }

    private companion object {
        const val TEST_WIDTH = 32
        const val TEST_HEIGHT = 32
        const val JPEG_QUALITY = 95
        const val APP1_TOTAL_SIZE = 32
    }
}
