/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readBytes
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS counterpart to `androidDeviceTest/.../ImageFormatMatrixTest.kt`. Verifies the encode side
 * of the new format matrix (CRA-72) actually round-trips through `CGImageDestination`:
 *
 *  - HEIC output: iOS 11+ — always supported at our iOS 15 floor, so this must succeed and the
 *    output must start with `ftyp` + a HEIC-family brand.
 *  - AVIF output: iOS 16+ — succeeds on 16+ simulators (CI runs macos-latest, iOS 17+), skipped
 *    on older runners where `CGImageDestinationCreateWithURL` returns nil and we surface a typed
 *    `UnsupportedOutputFormat` instead.
 *  - WEBP output: never wired through CGImageDestination on iOS. Always surfaces a typed
 *    `UnsupportedOutputFormat(format="webp")`.
 */
class IosImageFormatMatrixTest {

    private lateinit var testDir: String
    private val compressor = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-format-matrix-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun heicEncode_producesIsobmffWithHeicBrand() = runTest {
        @OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)
        val config = ImageCompressionConfig(format = ImageFormat.HEIC, quality = ENCODE_QUALITY)
        val input = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = testDir + "out.heic"

        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config,
        )

        assertTrue(result.isSuccess, "HEIC encode failed: ${result.exceptionOrNull()}")
        assertTrue(fileSize(output) > 0, "HEIC output is empty")
        assertIsobmffBrand(readBytes(output), expectedFamily = setOf("heic", "heix", "mif1", "heim", "heis"))
    }

    @Test
    fun avifEncode_producesIsobmffWithAvifBrand_whenPlatformSupportsIt() = runTest {
        @OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)
        val config = ImageCompressionConfig(format = ImageFormat.AVIF, quality = ENCODE_QUALITY)
        val input = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = testDir + "out.avif"

        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config,
        )

        if (iosMajorVersion() >= IOS_16_AVIF) {
            assertTrue(result.isSuccess, "AVIF encode failed on iOS ${iosMajorVersion()}: ${result.exceptionOrNull()}")
            assertTrue(fileSize(output) > 0, "AVIF output is empty")
            assertIsobmffBrand(readBytes(output), expectedFamily = setOf("avif", "avis", "mif1"))
        } else {
            // Pre-iOS 16 simulator — CGImageDestination returns nil for public.avif and the
            // compressor surfaces UnsupportedOutputFormat carrying minApi=16. Asserting this
            // branch keeps parity with Android's API-gated AVIF test.
            val err = result.exceptionOrNull()
            assertNotNull(err)
            assertTrue(
                err is ImageCompressionError.UnsupportedOutputFormat,
                "Expected UnsupportedOutputFormat on iOS ${iosMajorVersion()}, got " +
                    "${err::class.simpleName}: ${err.message}",
            )
            assertEquals("avif", err.format)
            assertEquals("ios", err.platform)
            assertEquals(IOS_16_AVIF, err.minApi)
        }
    }

    @Test
    fun webpOutput_alwaysFailsWithTypedUnsupportedOutputFormat() = runTest {
        val config = ImageCompressionConfig(format = ImageFormat.WEBP)
        val input = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = testDir + "out.webp"

        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config,
        )

        assertTrue(result.isFailure, "WEBP output must fail on iOS")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is ImageCompressionError.UnsupportedOutputFormat,
            "Expected UnsupportedOutputFormat, got ${err::class.simpleName}: ${err.message}",
        )
        assertEquals("webp", err.format)
        assertEquals("ios", err.platform)
    }

    @Test
    fun jpegOutput_stillRoundTripsAfterFormatMatrixExpansion() = runTest {
        val config = ImageCompressionConfig(format = ImageFormat.JPEG)
        val input = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val output = testDir + "out.jpg"

        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config,
        )

        assertTrue(result.isSuccess, "JPEG encode regressed: ${result.exceptionOrNull()}")
        val bytes = readBytes(output)
        assertTrue(
            bytes.size > 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte(),
            "JPEG output must start with SOI marker",
        )
    }

    /**
     * Assert that [bytes] is an ISOBMFF file whose `ftyp` box carries a major brand (or any
     * compatible brand) drawn from [expectedFamily]. Mirrors the sniffer contract in
     * `InputImageFormat.detectInputImageFormat` — both HEIC and AVIF ship inside ISOBMFF, so
     * the only reliable signature is the `ftyp` brand set.
     */
    private fun assertIsobmffBrand(bytes: ByteArray, expectedFamily: Set<String>) {
        assertTrue(bytes.size >= FTYP_MIN_SIZE, "Output too small to be ISOBMFF: ${bytes.size}")
        val ftyp = bytes.copyOfRange(FTYP_LABEL_OFFSET, FTYP_LABEL_OFFSET + FTYP_LABEL_LEN)
            .decodeToString()
        assertEquals("ftyp", ftyp, "Missing ftyp box at offset 4")
        val brands = mutableSetOf<String>()
        brands += bytes.copyOfRange(FTYP_MAJOR_OFFSET, FTYP_MAJOR_OFFSET + FTYP_LABEL_LEN).decodeToString()
        var offset = FTYP_COMPAT_OFFSET
        while (offset + FTYP_LABEL_LEN <= bytes.size) {
            val maybeBrand = bytes.copyOfRange(offset, offset + FTYP_LABEL_LEN).decodeToString()
            // Stop scanning once we leave ASCII — compatible brands end where the mdat/meta box starts.
            if (maybeBrand.any { it.code !in PRINTABLE_LO..PRINTABLE_HI }) break
            brands += maybeBrand
            offset += FTYP_LABEL_LEN
        }
        assertTrue(
            brands.any { it in expectedFamily },
            "Expected one brand from $expectedFamily, got $brands",
        )
    }

    private fun iosMajorVersion(): Int =
        UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0

    private companion object {
        const val IMAGE_SIDE = 256
        const val ENCODE_QUALITY = 80
        const val IOS_16_AVIF = 16
        const val FTYP_MIN_SIZE = 12
        const val FTYP_LABEL_OFFSET = 4
        const val FTYP_LABEL_LEN = 4
        const val FTYP_MAJOR_OFFSET = 8
        const val FTYP_COMPAT_OFFSET = 16
        const val PRINTABLE_LO = 0x20
        const val PRINTABLE_HI = 0x7E
    }
}
