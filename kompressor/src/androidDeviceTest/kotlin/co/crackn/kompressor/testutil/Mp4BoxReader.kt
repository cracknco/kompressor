package co.crackn.kompressor.testutil

import java.io.File

/**
 * Minimal ISO BMFF / MP4 top-level box walker, just enough to let tests assert the structural
 * layout of compressor outputs (e.g. verifying that the muxer did *not* front-load a gigantic
 * `free`-box `moov` reservation — the regression the padding fix in `Media3ExportRunner`'s
 * [co.crackn.kompressor.buildTightMp4MuxerFactory] addresses).
 *
 * A top-level box is encoded as: 4-byte big-endian size, 4-byte ASCII type, payload. Size `0`
 * means "box extends to end of file" (rare at top level) and size `1` means "next 8 bytes are
 * a 64-bit extended size" — we honour both.
 *
 * Returns the list of top-level box types and sizes in file order.
 */
data class Mp4Box(val type: String, val size: Long)

fun readTopLevelMp4Boxes(file: File): List<Mp4Box> {
    val bytes = file.readBytes()
    val boxes = mutableListOf<Mp4Box>()
    var pos = 0
    while (pos + BOX_HEADER_SIZE <= bytes.size) {
        val declaredSize = readUInt32BE(bytes, pos)
        val type = String(bytes, pos + SIZE_FIELD_BYTES, TYPE_FIELD_BYTES, Charsets.US_ASCII)
        val actualSize = when (declaredSize) {
            0L -> (bytes.size - pos).toLong()
            EXTENDED_SIZE_MARKER -> readUInt64BE(bytes, pos + BOX_HEADER_SIZE)
            else -> declaredSize
        }
        boxes += Mp4Box(type = type, size = actualSize)
        if (actualSize <= 0 || pos + actualSize > bytes.size) break
        pos += actualSize.toInt()
    }
    return boxes
}

private fun readUInt32BE(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl UINT32_SHIFT_3) or
        ((bytes[offset + 1].toLong() and 0xFF) shl UINT32_SHIFT_2) or
        ((bytes[offset + 2].toLong() and 0xFF) shl UINT32_SHIFT_1) or
        (bytes[offset + 3].toLong() and 0xFF)

@Suppress("MagicNumber")
private fun readUInt64BE(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return value
}

private const val SIZE_FIELD_BYTES = 4
private const val TYPE_FIELD_BYTES = 4
private const val BOX_HEADER_SIZE = SIZE_FIELD_BYTES + TYPE_FIELD_BYTES
private const val EXTENDED_SIZE_MARKER = 1L
private const val UINT32_SHIFT_1 = 8
private const val UINT32_SHIFT_2 = 16
private const val UINT32_SHIFT_3 = 24
