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

    /**
     * MP4 container: scan for `ftyp` + `moov` boxes plus a `vide` handler marker
     * to confirm the file is a plausible MP4 video container (not just audio-only).
     */
    fun isValidMp4(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_MP4_SIZE) return false
        val hasFtyp = scanForBox(bytes, 'f', 't', 'y', 'p')
        val hasMoov = scanForBox(bytes, 'm', 'o', 'o', 'v')
        // 'vide' is the video handler type written in trak/mdia/hdlr boxes
        val hasVideoHandler = scanForBox(bytes, 'v', 'i', 'd', 'e')
        return hasFtyp && hasMoov && hasVideoHandler
    }

    private fun scanForBox(bytes: ByteArray, c0: Char, c1: Char, c2: Char, c3: Char): Boolean {
        val limit = minOf(bytes.size - FOUR_CC_SIZE, BOX_SCAN_LIMIT)
        for (i in 0..limit) {
            if (bytes[i] == c0.code.toByte() && bytes[i + 1] == c1.code.toByte() &&
                bytes[i + 2] == c2.code.toByte() && bytes[i + 3] == c3.code.toByte()
            ) return true
        }
        return false
    }

    private const val FTYP_SCAN_LIMIT = 32
    private const val MIN_MP4_SIZE = 16
    private const val FOUR_CC_SIZE = 4
    private const val BOX_SCAN_LIMIT = 4096
}
