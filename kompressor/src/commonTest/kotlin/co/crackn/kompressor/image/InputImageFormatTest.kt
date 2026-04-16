/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package co.crackn.kompressor.image

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class InputImageFormatTest {

    @Test
    fun jpegMagicBytesAreClassifiedAsJpeg() {
        val header = ubyteArrayOf(0xFFu, 0xD8u, 0xFFu, 0xE0u, 0x00u, 0x10u).toByteArray()
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.JPEG
    }

    @Test
    fun pngMagicBytesAreClassifiedAsPng() {
        val header = ubyteArrayOf(0x89u, 0x50u, 0x4Eu, 0x47u, 0x0Du, 0x0Au, 0x1Au, 0x0Au).toByteArray()
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.PNG
    }

    @Test
    fun webpMagicBytesAreClassifiedAsWebp() {
        val header = "RIFF".toBytes() + ByteArray(4) + "WEBP".toBytes()
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.WEBP
    }

    @Test
    fun heicFtypBrandIsClassifiedAsHeic() {
        val header = ftypHeader(majorBrand = "heic")
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.HEIC
    }

    @Test
    fun avifFtypBrandIsClassifiedAsAvif() {
        val header = ftypHeader(majorBrand = "avif")
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.AVIF
    }

    @Test
    fun heifMif1FallbackIsClassifiedAsHeif() {
        val header = ftypHeader(majorBrand = "mif1")
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.HEIF
    }

    @Test
    fun heicCompatibleBrandIsDetectedWhenMajorBrandIsGeneric() {
        val header = ftypHeader(majorBrand = "mif1", compatibleBrands = listOf("heic"))
        detectInputImageFormat(header, extension = "") shouldBe InputImageFormat.HEIC
    }

    @Test
    fun dngExtensionFallbackWorksWithoutMagicBytes() {
        val header = ubyteArrayOf(0x49u, 0x49u, 0x2Au, 0x00u).toByteArray() // TIFF little-endian
        detectInputImageFormat(header, extension = "dng") shouldBe InputImageFormat.DNG
    }

    @Test
    fun heicExtensionFallbackWorksWhenMagicBytesAreMissing() {
        detectInputImageFormat(ByteArray(0), extension = "heic") shouldBe InputImageFormat.HEIC
    }

    @Test
    fun unknownBytesAndExtensionReturnUnknown() {
        detectInputImageFormat(byteArrayOf(0x00, 0x01, 0x02, 0x03), extension = "") shouldBe InputImageFormat.UNKNOWN
    }

    @Test
    fun magicBytesOverrideExtension() {
        val pngHeader = ubyteArrayOf(0x89u, 0x50u, 0x4Eu, 0x47u, 0x0Du, 0x0Au, 0x1Au, 0x0Au).toByteArray()
        detectInputImageFormat(pngHeader, extension = "jpg") shouldBe InputImageFormat.PNG
    }

    private fun ftypHeader(majorBrand: String, compatibleBrands: List<String> = emptyList()): ByteArray {
        val header = ByteArray(32)
        // 4B box size (arbitrary), 4B "ftyp", 4B major brand, 4B minor version, then compatible brands.
        "ftyp".toBytes().copyInto(header, destinationOffset = 4)
        majorBrand.toBytes().copyInto(header, destinationOffset = 8)
        // minor version bytes at 12..15 left as 0
        compatibleBrands.forEachIndexed { idx, brand ->
            brand.toBytes().copyInto(header, destinationOffset = 16 + idx * 4)
        }
        return header
    }

    private fun String.toBytes(): ByteArray = ByteArray(length) { idx -> this[idx].code.toByte() }
}
