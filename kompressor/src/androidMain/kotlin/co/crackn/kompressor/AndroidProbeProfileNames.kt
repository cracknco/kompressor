/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

/**
 * Pure helpers for the **probe path**, which operates on raw ints pulled from
 * [android.media.MediaFormat] (not the symbolic [android.media.MediaCodecInfo.CodecProfileLevel]
 * constants used in [AndroidCodecProfileNames]).
 *
 * The two tables can drift on some OEMs: `MediaFormat.KEY_PROFILE` sometimes
 * exposes values derived from container-level metadata rather than the codec's
 * `CodecProfileLevel` enum. These helpers encode the most common mappings and
 * return `null` on anything unrecognised so callers can surface a soft verdict.
 */
internal fun readProbeProfileName(mime: String?, profile: Int?): String? = when {
    mime == null || profile == null -> null
    else -> when (mime) {
        "video/hevc" -> hevcProbeProfileName(profile)
        "video/avc" -> avcProbeProfileName(profile)
        else -> null
    } ?: "Profile $profile"
}

internal fun hevcProbeProfileName(profile: Int): String? = when (profile) {
    HEVC_PROFILE_MAIN -> "Main"
    HEVC_PROFILE_MAIN10 -> "Main 10"
    HEVC_PROFILE_MAIN10_HDR10 -> "Main 10 HDR10"
    HEVC_PROFILE_MAIN10_HDR10_PLUS -> "Main 10 HDR10+"
    else -> null
}

internal fun avcProbeProfileName(profile: Int): String? = when (profile) {
    AVC_PROFILE_BASELINE -> "Baseline"
    AVC_PROFILE_MAIN -> "Main"
    AVC_PROFILE_HIGH -> "High"
    AVC_PROFILE_HIGH10 -> "High 10"
    else -> null
}

internal fun isHevc10BitProfile(profile: Int?): Boolean =
    profile == HEVC_PROFILE_MAIN10 ||
        profile == HEVC_PROFILE_MAIN10_HDR10 ||
        profile == HEVC_PROFILE_MAIN10_HDR10_PLUS

/**
 * Multi-signal bit-depth detection. Returns `null` when every signal is absent
 * so callers can surface a soft-warning verdict instead of a false-positive
 * 8-bit decision.
 *
 * @param explicitBitDepth value of `MediaFormat.KEY_BIT_DEPTH` (API 33+), or null.
 * @param colorFormat value of `MediaFormat.KEY_COLOR_FORMAT`, or null.
 */
internal fun readProbeBitDepth(
    mime: String?,
    profile: Int?,
    colorFormat: Int?,
    explicitBitDepth: Int?,
): Int? {
    if (explicitBitDepth != null) return explicitBitDepth
    return when {
        colorFormat == COLOR_FORMAT_YUVP010 -> TEN_BIT
        mime == "video/hevc" && profile != null && isHevc10BitProfile(profile) -> TEN_BIT
        mime == "video/avc" && profile == AVC_PROFILE_HIGH10 -> TEN_BIT
        mime == "video/hevc" && profile == HEVC_PROFILE_MAIN -> EIGHT_BIT
        // Only classify as 8-bit when the profile is one we actually recognise
        // as 8-bit — an unknown raw int on a High422/High444/OEM-specific value
        // stays null (→ Unknown) instead of being silently branded 8-bit.
        mime == "video/avc" && isKnown8BitAvcProfile(profile) -> EIGHT_BIT
        else -> null
    }
}

private fun isKnown8BitAvcProfile(profile: Int?): Boolean =
    profile == AVC_PROFILE_BASELINE ||
        profile == AVC_PROFILE_MAIN ||
        profile == AVC_PROFILE_HIGH

/**
 * HDR detection: either the transfer function is ST2084/HLG, or the BT.2020 +
 * 10-bit combination acts as a proxy when `KEY_COLOR_TRANSFER` is missing
 * (some MKV/Matroska packagings don't expose it).
 */
internal fun isProbeHdrFormat(
    colorTransfer: Int?,
    colorStandard: Int?,
    bitDepth: Int?,
): Boolean {
    val hdrTransfer = colorTransfer == HDR_TRANSFER_ST2084 || colorTransfer == HDR_TRANSFER_HLG
    val bt2020TenBit = colorStandard == COLOR_STANDARD_BT2020 && bitDepth == TEN_BIT
    return hdrTransfer || bt2020TenBit
}

private const val EIGHT_BIT = 8
private const val TEN_BIT = 10
private const val HDR_TRANSFER_ST2084 = 6
private const val HDR_TRANSFER_HLG = 7
private const val COLOR_STANDARD_BT2020 = 6
private const val COLOR_FORMAT_YUVP010 = 54

private const val HEVC_PROFILE_MAIN = 1
private const val HEVC_PROFILE_MAIN10 = 2
private const val HEVC_PROFILE_MAIN10_HDR10 = 4096
private const val HEVC_PROFILE_MAIN10_HDR10_PLUS = 8192
private const val AVC_PROFILE_BASELINE = 1
private const val AVC_PROFILE_MAIN = 2
private const val AVC_PROFILE_HIGH = 8
private const val AVC_PROFILE_HIGH10 = 16
