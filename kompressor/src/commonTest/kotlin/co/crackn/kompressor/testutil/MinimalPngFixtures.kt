/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

/**
 * Hand-builds minimal-but-spec-valid PNG files in pure Kotlin so both Android and iOS image tests
 * can exercise the platform decoders on 16-bit/channel, palette-indexed, and interlaced PNGs
 * without shipping binary assets in the repo.
 *
 * The IDAT payload is zlib-encoded using *stored* (uncompressed) DEFLATE blocks — this keeps the
 * implementation dependency-free, and libpng / ImageIO both accept stored blocks per RFC 1950.
 * Stored blocks are capped at 65 535 bytes; every fixture below stays well under that limit.
 *
 * PNG chunk structure reference: W3C PNG Specification, Second Edition.
 */
object MinimalPngFixtures {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * 2×2 RGBA image at 16 bits per channel. Filter byte 0 (None) + 4 pixels × 8 bytes per pixel
     * per row → 17 bytes per scanline. Deliberately uses 4 distinct high-bit colours so a decoder
     * that silently truncates to 8-bit produces a visibly different image.
     */
    fun rgba16bit2x2(): ByteArray {
        val width = 2
        val height = 2
        val bitDepth = 16
        val colorType = COLOR_TYPE_RGBA
        // Per scanline: 1 filter byte + width * 8 bytes (4 channels × 2 bytes)
        val scanline = ByteArray(1 + width * 8)
        val raw = ByteArray(scanline.size * height)
        // Row 0: red, green (each with R/G at max, others 0, alpha max)
        // Row 1: blue, white. 16-bit samples little-endian? No — PNG is big-endian.
        val pixels = listOf(
            // Row 0: red16, green16
            intArrayOf(0xFFFF, 0x0000, 0x0000, 0xFFFF),
            intArrayOf(0x0000, 0xFFFF, 0x0000, 0xFFFF),
            // Row 1: blue16, white16
            intArrayOf(0x0000, 0x0000, 0xFFFF, 0xFFFF),
            intArrayOf(0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF),
        )
        var idx = 0
        for (y in 0 until height) {
            raw[idx++] = FILTER_NONE
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                for (c in 0 until 4) {
                    raw[idx++] = ((p[c] shr BYTE_SHIFT_1) and 0xFF).toByte()
                    raw[idx++] = (p[c] and 0xFF).toByte()
                }
            }
        }
        return buildPng(
            ihdr = buildIhdr(width, height, bitDepth, colorType, interlace = INTERLACE_NONE),
            idat = zlibStored(raw),
            extraChunks = emptyList(),
        )
    }

    /**
     * 4×4 palette-indexed PNG with a 4-entry PLTE. Each row: 1 filter byte + 4 pixels × 1 byte.
     * Indices rotate 0,1,2,3 per row so the decoded output has a clearly-identifiable pattern.
     */
    fun indexed4x4(): ByteArray {
        val width = 4
        val height = 4
        val bitDepth = 8
        val colorType = COLOR_TYPE_INDEXED
        val palette = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, // red
            0x00, 0xFF.toByte(), 0x00, // green
            0x00, 0x00, 0xFF.toByte(), // blue
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // white
        )
        val scanline = 1 + width
        val raw = ByteArray(scanline * height)
        var idx = 0
        for (y in 0 until height) {
            raw[idx++] = FILTER_NONE
            for (x in 0 until width) {
                raw[idx++] = ((x + y) % PALETTE_SIZE).toByte()
            }
        }
        return buildPng(
            ihdr = buildIhdr(width, height, bitDepth, colorType, interlace = INTERLACE_NONE),
            idat = zlibStored(raw),
            extraChunks = listOf(chunk("PLTE", palette)),
        )
    }

    /**
     * 4×4 RGBA interlaced (Adam7) PNG. Adam7 splits the image into 7 passes; for a 4×4 image the
     * passes and their dimensions are:
     *   pass1: 1×1  pass2: 1×1  pass3: 2×1  pass4: 2×2  pass5: 2×2  pass6: 4×2  pass7: 4×2
     * Each pass produces its own set of scanlines (filter byte + pixel bytes per row). The full
     * IDAT is the concatenation of every pass's scanlines, zlib-encoded in one shot.
     */
    fun rgbaInterlaced4x4(): ByteArray {
        val width = 4
        val height = 4
        val bitDepth = 8
        val colorType = COLOR_TYPE_RGBA
        val passDims = listOf(
            1 to 1, 1 to 1, 2 to 1, 2 to 2, 2 to 2, 4 to 2, 4 to 2,
        )
        // Simplification: emit zero pixels (all channels 0, alpha 255) for every pass. A valid
        // fixture the decoder can parse is all we need — the image tests only assert compress()
        // succeeds, not pixel-level content.
        val rawChunks = mutableListOf<ByteArray>()
        for ((w, h) in passDims) {
            if (w == 0 || h == 0) continue
            val scanline = ByteArray(1 + w * 4)
            // filter 0, RGB=0, A=255
            for (i in 0 until w) {
                scanline[1 + i * 4 + 3] = 0xFF.toByte()
            }
            val passRaw = ByteArray(scanline.size * h)
            for (row in 0 until h) {
                scanline.copyInto(passRaw, row * scanline.size)
            }
            rawChunks += passRaw
        }
        val totalSize = rawChunks.sumOf { it.size }
        val raw = ByteArray(totalSize)
        var off = 0
        for (c in rawChunks) {
            c.copyInto(raw, off)
            off += c.size
        }
        return buildPng(
            ihdr = buildIhdr(width, height, bitDepth, colorType, interlace = INTERLACE_ADAM7),
            idat = zlibStored(raw),
            extraChunks = emptyList(),
        )
    }

    /**
     * A deliberately-truncated JPEG: SOI + APP0 header with a declared length but the rest of the
     * stream clipped. Any conforming decoder must reject this with a decode failure.
     */
    val TRUNCATED_JPEG: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00,
    )

    /**
     * Surgically replace the EXIF APP1 (`0xFFE1`) payload in a valid JPEG with garbage bytes,
     * preserving the APP1 length field so the overall file still has structurally-valid segment
     * framing. Returns the original bytes unchanged if no APP1 marker is present.
     */
    fun corruptExifPayload(jpeg: ByteArray): ByteArray {
        var i = 0
        while (i < jpeg.size - 3) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xE1.toByte()) {
                val len = ((jpeg[i + 2].toInt() and 0xFF) shl BYTE_SHIFT_1) or
                    (jpeg[i + 3].toInt() and 0xFF)
                // len includes the two length bytes themselves — payload is len - 2 bytes after.
                val payloadStart = i + 4
                val payloadLen = len - 2
                if (payloadStart + payloadLen > jpeg.size) return jpeg.copyOf()
                val out = jpeg.copyOf()
                for (j in 0 until payloadLen) {
                    out[payloadStart + j] = (0xA5.toByte())
                }
                return out
            }
            i++
        }
        return jpeg.copyOf()
    }

    private fun buildIhdr(
        width: Int,
        height: Int,
        bitDepth: Int,
        colorType: Int,
        interlace: Int,
    ): ByteArray {
        val data = ByteArray(IHDR_SIZE)
        writeIntBE(data, 0, width)
        writeIntBE(data, 4, height)
        data[8] = bitDepth.toByte()
        data[9] = colorType.toByte()
        data[10] = 0 // compression: DEFLATE
        data[11] = 0 // filter: adaptive
        data[12] = interlace.toByte()
        return data
    }

    private fun buildPng(
        ihdr: ByteArray,
        idat: ByteArray,
        extraChunks: List<ByteArray>,
    ): ByteArray {
        val parts = mutableListOf<ByteArray>()
        parts += PNG_SIGNATURE
        parts += chunk("IHDR", ihdr)
        for (c in extraChunks) parts += c
        parts += chunk("IDAT", idat)
        parts += chunk("IEND", ByteArray(0))
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArray(CHUNK_HEADER_SIZE + data.size + CHUNK_CRC_SIZE)
        writeIntBE(out, 0, data.size)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        data.copyInto(out, CHUNK_HEADER_SIZE)
        val crcInput = ByteArray(4 + data.size)
        for (i in 0 until 4) crcInput[i] = out[4 + i]
        data.copyInto(crcInput, 4)
        writeIntBE(out, CHUNK_HEADER_SIZE + data.size, crc32(crcInput))
        return out
    }

    /** Wraps [raw] in a zlib stream using DEFLATE stored (non-compressed) blocks. */
    private fun zlibStored(raw: ByteArray): ByteArray {
        val blocks = mutableListOf<ByteArray>()
        var off = 0
        while (off < raw.size) {
            val remaining = raw.size - off
            val blockLen = minOf(remaining, MAX_STORED_BLOCK)
            val isFinal = off + blockLen == raw.size
            val header = byteArrayOf(if (isFinal) 0x01 else 0x00)
            val lenLe = byteArrayOf(
                (blockLen and 0xFF).toByte(),
                ((blockLen shr BYTE_SHIFT_1) and 0xFF).toByte(),
            )
            val nlenLe = byteArrayOf(
                (blockLen.inv() and 0xFF).toByte(),
                ((blockLen.inv() shr BYTE_SHIFT_1) and 0xFF).toByte(),
            )
            val payload = ByteArray(blockLen)
            raw.copyInto(payload, 0, off, off + blockLen)
            val b = ByteArray(1 + 2 + 2 + blockLen)
            header.copyInto(b, 0)
            lenLe.copyInto(b, 1)
            nlenLe.copyInto(b, 3)
            payload.copyInto(b, 5)
            blocks += b
            off += blockLen
        }
        if (blocks.isEmpty()) {
            // Empty final stored block for zero-byte payload.
            blocks += byteArrayOf(0x01, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
        }
        val adler = adler32(raw)
        val blocksSize = blocks.sumOf { it.size }
        val out = ByteArray(ZLIB_HEADER_SIZE + blocksSize + ADLER_SIZE)
        // zlib header: 0x78 (deflate, 32 KB window) + 0x01 (level 0, fcheck)
        out[0] = 0x78
        out[1] = 0x01
        var off2 = 2
        for (b in blocks) {
            b.copyInto(out, off2)
            off2 += b.size
        }
        writeIntBE(out, ZLIB_HEADER_SIZE + blocksSize, adler)
        return out
    }

    private fun writeIntBE(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr BYTE_SHIFT_3) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr BYTE_SHIFT_2) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr BYTE_SHIFT_1) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }

    private fun crc32(data: ByteArray): Int {
        var c = CRC_INIT.toInt()
        for (b in data) {
            val idx = (c xor b.toInt()) and 0xFF
            c = CRC_TABLE[idx] xor (c ushr BYTE_SHIFT_1)
        }
        return c xor CRC_INIT.toInt()
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % ADLER_MOD
            b = (b + a) % ADLER_MOD
        }
        return (b shl BYTE_SHIFT_2) or a
    }

    private val CRC_TABLE: IntArray = IntArray(CRC_TABLE_SIZE).also { table ->
        for (n in 0 until CRC_TABLE_SIZE) {
            var c = n
            for (k in 0 until BYTE_SHIFT_1) {
                c = if (c and 1 != 0) CRC_POLY xor (c ushr 1) else c ushr 1
            }
            table[n] = c
        }
    }

    private const val IHDR_SIZE = 13
    private const val CHUNK_HEADER_SIZE = 8
    private const val CHUNK_CRC_SIZE = 4
    private const val ZLIB_HEADER_SIZE = 2
    private const val ADLER_SIZE = 4
    private const val MAX_STORED_BLOCK = 65_535
    private const val ADLER_MOD = 65_521
    private const val COLOR_TYPE_INDEXED = 3
    private const val COLOR_TYPE_RGBA = 6
    private const val INTERLACE_NONE = 0
    private const val INTERLACE_ADAM7 = 1
    private const val FILTER_NONE: Byte = 0x00
    private const val PALETTE_SIZE = 4
    private const val BYTE_SHIFT_1 = 8
    private const val BYTE_SHIFT_2 = 16
    private const val BYTE_SHIFT_3 = 24

    private const val CRC_TABLE_SIZE = 256
    private const val CRC_INIT = 0xFFFFFFFFL
    private const val CRC_POLY = -0x12477CE0 // 0xEDB88320 as signed Int
}
