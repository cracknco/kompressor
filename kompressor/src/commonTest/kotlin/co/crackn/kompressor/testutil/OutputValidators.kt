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

    /** M4A/MP4: scan first 32 bytes for `ftyp` box (may be preceded by `free`/`wide` boxes). */
    fun isValidM4a(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val scanLimit = minOf(bytes.size - 4, FTYP_SCAN_LIMIT)
        for (i in 0..scanLimit) {
            if (bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
            ) return true
        }
        return false
    }

    private const val FTYP_SCAN_LIMIT = 32

}
