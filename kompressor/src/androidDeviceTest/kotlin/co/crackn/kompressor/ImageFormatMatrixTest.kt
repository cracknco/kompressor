/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createTestImage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Device sweep of the expanded image-format matrix (CRA-72).
 *
 * The commonTest suite already covers the pure gate / sniffer logic. This file exercises the
 * real Android platform decoders + encoders on the Firebase Test Lab Pixel 6 (API 33):
 *
 *  - AVIF output is rejected on API 33 (min is 34) with a typed `UnsupportedOutputFormat`.
 *  - HEIC output is always rejected on Android (sentinel `Int.MAX_VALUE`).
 *  - WEBP output round-trips and produces a valid RIFF/WEBP file.
 *  - HEIC / AVIF input is sniffed via ISOBMFF `ftyp` brand; malformed payloads surface as
 *    `DecodingFailed` (not a crash) once the gate has passed.
 *  - DNG input is recognised by extension fallback and surfaces `DecodingFailed` on the
 *    synthetic TIFF bytes (no real DNG decoder fixture is shipped yet; CRA-5 will add one).
 */
class ImageFormatMatrixTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-image-format-matrix").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun avifOutputOnApi33_failsWithTypedUnsupportedOutputFormat() = runTest {
        // Pixel 6 FTL runtime is API 33. AVIF output was added in API 34 so the gate must
        // refuse before touching the encoder. On an API-34+ device this test is skipped — the
        // encoder path is exercised by a future device matrix (tracked via FTL coverage).
        assumeTrue(
            "AVIF-output rejection is only observable below API 34",
            Build.VERSION.SDK_INT < API_34_AVIF,
        )
        @OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)
        val config = ImageCompressionConfig(format = ImageFormat.AVIF)
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = File(tempDir, "avif_out.avif")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config,
        )

        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.UnsupportedOutputFormat,
            "Expected UnsupportedOutputFormat, got ${err::class.simpleName}: ${err.message}",
        )
        assertEquals("avif", err.format)
        assertEquals("android", err.platform)
        assertEquals(API_34_AVIF, err.minApi)
        assertTrue(!output.exists() || output.length() == 0L, "No partial AVIF output on gate failure")
    }

    @Test
    fun heicOutputOnAnyApi_failsWithTypedUnsupportedOutputFormat() = runTest {
        @OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)
        val config = ImageCompressionConfig(format = ImageFormat.HEIC)
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = File(tempDir, "heic_out.heic")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config,
        )

        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.UnsupportedOutputFormat,
            "Expected UnsupportedOutputFormat, got ${err::class.simpleName}: ${err.message}",
        )
        assertEquals("heic", err.format)
        assertEquals("android", err.platform)
    }

    @Test
    fun webpOutputRoundTrip_producesValidRiffWebp() = runTest {
        val config = ImageCompressionConfig(format = ImageFormat.WEBP, quality = WEBP_QUALITY)
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = File(tempDir, "webp_out.webp")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config,
        )

        assertTrue(result.isSuccess, "WEBP compression failed: ${result.exceptionOrNull()}")
        val bytes = output.readBytes()
        // RIFF....WEBP — first 4 bytes "RIFF", bytes 8..11 "WEBP".
        assertTrue(bytes.size > RIFF_MIN_SIZE, "Output too small to be a RIFF/WEBP: ${bytes.size}")
        assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString(), "Missing RIFF header")
        assertEquals("WEBP", bytes.copyOfRange(8, 12).decodeToString(), "Missing WEBP fourcc")
    }

    @Test
    fun heicInputSniff_passesGateOnApi30Plus_thenDecoderFailsOnSyntheticPayload() = runTest {
        // On API 30+ the gate allows HEIC input; a malformed ISOBMFF body surfaces as
        // DecodingFailed from the platform decoder rather than UnsupportedInputFormat.
        // That's the contract we want: typed decode failure, not a crash.
        assumeTrue("HEIC input requires API 30+", Build.VERSION.SDK_INT >= API_30_HEIC)
        val heicBytes = syntheticIsobmff(majorBrand = "heic")
        val input = File(tempDir, "synth.heic").apply { writeBytes(heicBytes) }
        val output = File(tempDir, "heic_synth_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isFailure, "Synthetic HEIC payload should not decode")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.DecodingFailed,
            "Expected DecodingFailed (gate passed, decoder refused), got " +
                "${err::class.simpleName}: ${err.message}",
        )
    }

    @Test
    fun avifInputSniff_passesGateOnApi31Plus_thenDecoderFailsOnSyntheticPayload() = runTest {
        assumeTrue("AVIF input requires API 31+", Build.VERSION.SDK_INT >= API_31_AVIF)
        val avifBytes = syntheticIsobmff(majorBrand = "avif")
        val input = File(tempDir, "synth.avif").apply { writeBytes(avifBytes) }
        val output = File(tempDir, "avif_synth_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isFailure, "Synthetic AVIF payload should not decode")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.DecodingFailed,
            "Expected DecodingFailed, got ${err::class.simpleName}: ${err.message}",
        )
    }

    @Test
    fun dngExtension_routesThroughDecoderAndFailsOnSyntheticTiff() = runTest {
        // DNG isn't gated on Android — decode is always attempted. BitmapFactory refuses a
        // bare TIFF header without IFDs and surfaces as DecodingFailed. A real DNG fixture
        // round-trip is tracked by CRA-5 (fixture prep).
        val tiffBytes = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, // TIFF little-endian magic
            0x08, 0x00, 0x00, 0x00, // offset to first IFD (not actually present)
        )
        val input = File(tempDir, "synth.dng").apply { writeBytes(tiffBytes) }
        val output = File(tempDir, "dng_synth_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isFailure, "Synthetic DNG should not decode")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.DecodingFailed,
            "Expected DecodingFailed for synthetic DNG, got ${err::class.simpleName}: ${err.message}",
        )
    }

    @Test
    fun jpegRoundTrip_stillWorksAfterFormatMatrixExpansion() = runTest {
        // Sanity regression — the default JPEG path must not have been disturbed by the
        // gate additions. Explicit format = JPEG exercises the default output branch.
        val config = ImageCompressionConfig(format = ImageFormat.JPEG)
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = File(tempDir, "jpeg_out.jpg")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config,
        )

        assertTrue(result.isSuccess, "JPEG compression regressed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()))
    }

    /**
     * Build a minimal ISOBMFF container with a correctly-formed `ftyp` box carrying
     * [majorBrand]. Enough bytes for `detectInputImageFormat` to classify the container;
     * no media samples, so the platform decoder will always reject it.
     */
    private fun syntheticIsobmff(majorBrand: String): ByteArray {
        require(majorBrand.length == 4) { "Major brand must be 4 ASCII chars" }
        val box = ByteArray(FTYP_BOX_LEN)
        // 4B big-endian box size = 32
        box[3] = FTYP_BOX_LEN.toByte()
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(box, destinationOffset = 4)
        majorBrand.toByteArray(Charsets.US_ASCII).copyInto(box, destinationOffset = 8)
        // minor version = 0, compatible brands[0] = majorBrand (sanity), rest = 0
        majorBrand.toByteArray(Charsets.US_ASCII).copyInto(box, destinationOffset = 16)
        return box
    }

    private companion object {
        const val IMAGE_SIDE = 200
        const val WEBP_QUALITY = 80
        const val API_30_HEIC = 30
        const val API_31_AVIF = 31
        const val API_34_AVIF = 34
        const val RIFF_MIN_SIZE = 12
        const val FTYP_BOX_LEN = 32
    }
}
