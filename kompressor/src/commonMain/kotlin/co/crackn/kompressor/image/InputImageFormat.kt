/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

// The sniffer is a single cohesive pipeline — splitting it across files would scatter
// ISOBMFF parsing logic that only makes sense when read together. Threshold exceeded by one.
@file:Suppress("TooManyFunctions")

package co.crackn.kompressor.image

/**
 * Recognised image input containers. Used internally to gate platform-version-dependent formats
 * (HEIC/HEIF, AVIF) before the platform decoder is invoked so the caller sees a typed
 * [ImageCompressionError.UnsupportedInputFormat] rather than a generic decode failure.
 *
 * [UNKNOWN] is the "not matched by sniffer" bucket — the compressor still attempts to decode
 * (platform decoders handle plenty of formats not covered by magic-byte sniffing).
 */
internal enum class InputImageFormat(
    /** Stable identifier used in error messages and docs. */
    val id: String,
) {
    JPEG("jpeg"),
    PNG("png"),
    WEBP("webp"),
    GIF("gif"),
    BMP("bmp"),
    HEIC("heic"),
    HEIF("heif"),
    AVIF("avif"),

    /** DNG (raw camera). Always an extension-only match; container is a TIFF variant. */
    DNG("dng"),

    /** Container not matched by the sniffer. Decode is still attempted. */
    UNKNOWN("unknown"),
}

/**
 * Minimum number of header bytes required to disambiguate every format in [InputImageFormat]
 * via magic bytes. 32 covers JPEG (3B), PNG (8B), WebP RIFF (12B), GIF (6B), BMP (2B), and
 * ISOBMFF (`ftyp` box at offset 4–12B plus up to 16B of compatible brands).
 *
 * Pragmatically a budget choice: real-world ISOBMFF files declare their major brand at offset 8
 * and the brand we actually care about in the first compatible-brand slot at offset 16, so 32B
 * is sufficient for every container we support. Files that bury the brand deeper in `compatible`
 * entries would need a streaming parser — out of scope for this sniffer.
 */
internal const val IMAGE_SNIFF_BYTES: Int = 32

/**
 * Lower-case filename extension without the leading dot (`"heic"`, `"jpg"`). Empty string when
 * the path has no extension. Platform-agnostic — used by both compressors to feed
 * [detectInputImageFormat].
 */
internal fun fileExtension(inputPath: String): String {
    val lastDot = inputPath.lastIndexOf('.')
    if (lastDot < 0 || lastDot == inputPath.length - 1) return ""
    return inputPath.substring(lastDot + 1).lowercase()
}

/**
 * Classify the input container from its leading [header] bytes and filename [extension].
 * Magic bytes take precedence; extension is a fallback (and the only signal for DNG, whose
 * TIFF header is indistinguishable from other TIFF-based formats without parsing IFDs).
 *
 * [extension] is the lowercase file extension without the leading dot (`"heic"`, `"jpg"`, `"dng"`).
 * Pass an empty string when unknown.
 */
internal fun detectInputImageFormat(header: ByteArray, extension: String): InputImageFormat =
    detectFromMagicBytes(header) ?: extensionFallback(extension)

/**
 * Magic-byte table driving [detectFromMagicBytes]. Each entry pairs a prefix signature with the
 * format it implies. Ordered generally by specificity; ISOBMFF / WebP are handled separately
 * because they need offset-based brand checks beyond a fixed prefix.
 */
private val MAGIC_BYTE_SIGNATURES: List<Pair<ByteArray, InputImageFormat>> = listOf(
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to InputImageFormat.JPEG,
    byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    ) to InputImageFormat.PNG,
    "GIF87a".encodeToByteArray() to InputImageFormat.GIF,
    "GIF89a".encodeToByteArray() to InputImageFormat.GIF,
    "BM".encodeToByteArray() to InputImageFormat.BMP,
)

@Suppress("ReturnCount")
private fun detectFromMagicBytes(header: ByteArray): InputImageFormat? {
    for ((signature, format) in MAGIC_BYTE_SIGNATURES) {
        if (matchesPrefix(header, signature)) return format
    }
    if (isRiffWebp(header)) return InputImageFormat.WEBP
    if (isIsobmff(header)) return heifBrandToFormat(header)
    return null
}

private fun matchesPrefix(header: ByteArray, signature: ByteArray): Boolean =
    header.size >= signature.size && signature.indices.all { header[it] == signature[it] }

// WebP: "RIFF" at 0..3, "WEBP" at 8..11
private fun isRiffWebp(header: ByteArray): Boolean =
    asciiAt(header, 0) == "RIFF" && asciiAt(header, WEBP_FOURCC_OFFSET) == "WEBP"

// ISOBMFF: "ftyp" at offset 4
private fun isIsobmff(header: ByteArray): Boolean = asciiAt(header, FTYP_LABEL_OFFSET) == "ftyp"

private const val WEBP_FOURCC_OFFSET = 8
private const val FTYP_LABEL_OFFSET = 4
private const val FTYP_MAJOR_OFFSET = 8
private const val FTYP_COMPAT_OFFSET = 16
private const val FTYP_BRAND_LEN = 4
private const val PRINTABLE_LO = 0x20
private const val PRINTABLE_HI = 0x7E

private fun extensionFallback(extension: String): InputImageFormat = when (extension) {
    "jpg", "jpeg" -> InputImageFormat.JPEG
    "png" -> InputImageFormat.PNG
    "webp" -> InputImageFormat.WEBP
    "gif" -> InputImageFormat.GIF
    "bmp" -> InputImageFormat.BMP
    "heic", "heics" -> InputImageFormat.HEIC
    "heif", "heifs" -> InputImageFormat.HEIF
    "avif" -> InputImageFormat.AVIF
    "dng" -> InputImageFormat.DNG
    else -> InputImageFormat.UNKNOWN
}

/**
 * Resolve the specific HEIC/HEIF/AVIF variant from an ISOBMFF `ftyp` box. "mif1"/"msf1" are
 * the generic HEIF brands Apple and Android use as a *major* brand for HEIC files, so when we
 * see a HEIF major we keep scanning compatible brands to find a more specific match (HEIC or
 * AVIF) — that's why a `mif1`+`heic` file is classified as HEIC. For an unrecognised major we
 * also fall through to compatible brands; [scanCompatibleBrands] itself defaults to HEIF when
 * nothing in the box matches our table, matching the ISOBMFF baseline the outer caller expects.
 */
private fun heifBrandToFormat(header: ByteArray): InputImageFormat {
    val major = asciiAt(header, FTYP_MAJOR_OFFSET)?.let(::brandToFormat) ?: InputImageFormat.UNKNOWN
    return when (major) {
        InputImageFormat.UNKNOWN, InputImageFormat.HEIF -> scanCompatibleBrands(header)
        else -> major
    }
}

private fun scanCompatibleBrands(header: ByteArray): InputImageFormat {
    val maxOffset = header.size - FTYP_BRAND_LEN
    val sequence = generateSequence(FTYP_COMPAT_OFFSET) { prev ->
        val next = prev + FTYP_BRAND_LEN
        if (next <= maxOffset) next else null
    }
    val hit = sequence
        .map { offset -> asciiAt(header, offset) }
        .takeWhile { it != null }
        .map { brand -> brandToFormat(brand!!) }
        .firstOrNull { it != InputImageFormat.UNKNOWN }
    return hit ?: InputImageFormat.HEIF
}

private fun brandToFormat(brand: String): InputImageFormat = when (brand) {
    "heic", "heix", "hevc", "heim", "heis", "hevm", "hevs" -> InputImageFormat.HEIC
    "mif1", "msf1" -> InputImageFormat.HEIF
    "avif", "avis" -> InputImageFormat.AVIF
    else -> InputImageFormat.UNKNOWN
}

private fun asciiAt(header: ByteArray, offset: Int): String? {
    if (offset + FTYP_BRAND_LEN > header.size) return null
    val chars = CharArray(FTYP_BRAND_LEN)
    var allPrintable = true
    for (i in 0 until FTYP_BRAND_LEN) {
        val b = header[offset + i].toInt() and 0xFF
        if (b < PRINTABLE_LO || b > PRINTABLE_HI) {
            allPrintable = false
            break
        }
        chars[i] = b.toChar()
    }
    return if (allPrintable) chars.concatToString() else null
}
