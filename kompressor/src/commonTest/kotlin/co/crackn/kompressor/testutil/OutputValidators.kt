package co.crackn.kompressor.testutil

/**
 * Lightweight container-format validators that check magic bytes and structural markers.
 *
 * These do NOT fully parse the containers — they verify just enough to confirm
 * that the output is a plausible file of the claimed type, which catches encoder
 * failures and truncated writes.
 */
object OutputValidators {

    /** JPEG starts with `FF D8 FF` and ends with `FF D9`. */
    fun isValidJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val soi = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val eoi = bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
        return soi && eoi
    }

    /** M4A/MP4 files have an `ftyp` box near the start (within the first 12 bytes). */
    fun isValidM4a(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // The ftyp box: bytes 4..7 should be "ftyp"
        val ftyp = bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        return ftyp
    }

    /** MP4 container — same ftyp check as M4A (they share the ISO base media format). */
    fun isValidMp4(bytes: ByteArray): Boolean = isValidM4a(bytes)
}
