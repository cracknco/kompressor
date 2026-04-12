@file:Suppress("TooManyFunctions")

package co.crackn.kompressor

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Queries the Android device for its full codec capability matrix via
 * [MediaCodecList.REGULAR_CODECS]. Called by the sample app's Capabilities
 * screen and internally by [Kompressor.canCompress].
 */
public actual fun queryDeviceCapabilities(): DeviceCapabilities {
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    val video = mutableListOf<CodecSupport>()
    val audio = mutableListOf<CodecSupport>()
    codecs.forEach { info ->
        info.supportedTypes.forEach { type ->
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@forEach
            val entry = caps.toCodecSupport(info, type)
            if (type.startsWith("video/")) video += entry else if (type.startsWith("audio/")) audio += entry
        }
    }
    return DeviceCapabilities(video = video, audio = audio, deviceSummary = androidDeviceSummary())
}

private fun androidDeviceSummary(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

private fun CodecCapabilities.toCodecSupport(info: MediaCodecInfo, mime: String): CodecSupport {
    val role = if (info.isEncoder) CodecSupport.Role.Encoder else CodecSupport.Role.Decoder
    val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else false
    val profiles = readProfiles(mime)
    val supports10Bit = has10BitSupport(mime)
    val supportsHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        isFeatureSupported(CodecCapabilities.FEATURE_HdrEditing)
    } else {
        false
    }
    return if (mime.startsWith("video/")) {
        toVideoCodec(info, mime, role, hardware, profiles, supports10Bit, supportsHdr)
    } else {
        toAudioCodec(info, mime, role, hardware, profiles)
    }
}

private fun CodecCapabilities.readProfiles(mime: String): List<String> =
    profileLevels.orEmpty()
        .map { humanProfileName(mime, it) }
        .distinct()
        .filter { it.isNotEmpty() }

private fun CodecCapabilities.has10BitSupport(mime: String): Boolean =
    profileLevels.orEmpty().any { isTenBitProfile(mime, it.profile) } ||
        (colorFormats ?: intArrayOf()).any { it == COLOR_FORMAT_YUVP010 }

@Suppress("LongParameterList")
private fun CodecCapabilities.toVideoCodec(
    info: MediaCodecInfo,
    mime: String,
    role: CodecSupport.Role,
    hardware: Boolean,
    profiles: List<String>,
    supports10Bit: Boolean,
    supportsHdr: Boolean,
): CodecSupport {
    val vc = videoCapabilities
    return CodecSupport(
        mimeType = mime,
        role = role,
        codecName = info.name,
        hardwareAccelerated = hardware,
        profiles = profiles,
        maxResolution = vc?.let { it.supportedWidths.upper to it.supportedHeights.upper },
        maxFrameRate = vc?.supportedFrameRates?.upper,
        maxBitrate = vc?.bitrateRange?.upper?.toLong(),
        supports10Bit = supports10Bit,
        supportsHdr = supportsHdr,
    )
}

private fun CodecCapabilities.toAudioCodec(
    info: MediaCodecInfo,
    mime: String,
    role: CodecSupport.Role,
    hardware: Boolean,
    profiles: List<String>,
): CodecSupport {
    val ac = audioCapabilities
    return CodecSupport(
        mimeType = mime,
        role = role,
        codecName = info.name,
        hardwareAccelerated = hardware,
        profiles = profiles,
        maxBitrate = ac?.bitrateRange?.upper?.toLong(),
        audioSampleRates = ac?.supportedSampleRates?.toList().orEmpty(),
        audioMaxChannels = ac?.maxInputChannelCount,
    )
}

private fun humanProfileName(mime: String, pl: CodecProfileLevel): String = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_AVC -> avcProfileName(pl.profile)
    MediaFormat.MIMETYPE_VIDEO_HEVC -> hevcProfileName(pl.profile)
    MediaFormat.MIMETYPE_VIDEO_VP9 -> vp9ProfileName(pl.profile)
    MediaFormat.MIMETYPE_VIDEO_AV1 -> av1ProfileName(pl.profile)
    else -> ""
}

private fun avcProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.AVCProfileBaseline -> "Baseline"
    CodecProfileLevel.AVCProfileMain -> "Main"
    CodecProfileLevel.AVCProfileExtended -> "Extended"
    CodecProfileLevel.AVCProfileHigh -> "High"
    CodecProfileLevel.AVCProfileHigh10 -> "High 10"
    CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
    CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
    CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
    CodecProfileLevel.AVCProfileConstrainedHigh -> "Constrained High"
    else -> ""
}

private fun hevcProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.HEVCProfileMain -> "Main"
    CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
    CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
    CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
    CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
    else -> ""
}

private fun vp9ProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.VP9Profile0 -> "Profile 0"
    CodecProfileLevel.VP9Profile1 -> "Profile 1"
    CodecProfileLevel.VP9Profile2 -> "Profile 2 (10-bit)"
    CodecProfileLevel.VP9Profile3 -> "Profile 3 (10-bit 4:4:4)"
    CodecProfileLevel.VP9Profile2HDR -> "Profile 2 HDR"
    CodecProfileLevel.VP9Profile3HDR -> "Profile 3 HDR"
    CodecProfileLevel.VP9Profile2HDR10Plus -> "Profile 2 HDR10+"
    CodecProfileLevel.VP9Profile3HDR10Plus -> "Profile 3 HDR10+"
    else -> ""
}

private fun av1ProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.AV1ProfileMain8 -> "Main 8-bit"
    CodecProfileLevel.AV1ProfileMain10 -> "Main 10-bit"
    CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
    CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
    else -> ""
}

private fun isTenBitProfile(mime: String, profile: Int): Boolean = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> profile == CodecProfileLevel.HEVCProfileMain10 ||
        profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
        profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus
    MediaFormat.MIMETYPE_VIDEO_AVC -> profile == CodecProfileLevel.AVCProfileHigh10
    MediaFormat.MIMETYPE_VIDEO_VP9 -> profile == CodecProfileLevel.VP9Profile2 ||
        profile == CodecProfileLevel.VP9Profile3 ||
        profile == CodecProfileLevel.VP9Profile2HDR ||
        profile == CodecProfileLevel.VP9Profile3HDR
    MediaFormat.MIMETYPE_VIDEO_AV1 -> profile == CodecProfileLevel.AV1ProfileMain10 ||
        profile == CodecProfileLevel.AV1ProfileMain10HDR10 ||
        profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus
    else -> false
}

// MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (API 29+).
private const val COLOR_FORMAT_YUVP010 = 54
