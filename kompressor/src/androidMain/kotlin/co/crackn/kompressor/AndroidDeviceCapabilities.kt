/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.os.Build

/**
 * Queries the Android device for its full codec capability matrix via
 * [MediaCodecList.REGULAR_CODECS]. Called by the sample app's Capabilities
 * screen and internally by [Kompressor.canCompress].
 *
 * Pure int→string / int→boolean mapping lives in [humanProfileName] and
 * [isTenBitProfile] (file [AndroidCodecProfileNames.kt]) — host-tested.
 * This file contains the device-binder-IPC glue only.
 */
public actual fun queryDeviceCapabilities(): DeviceCapabilities {
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    val video = mutableListOf<CodecSupport.Video>()
    val audio = mutableListOf<CodecSupport.Audio>()
    codecs.forEach { info ->
        info.supportedTypes.forEach { type ->
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@forEach
            when {
                type.startsWith("video/") -> video += caps.toVideoCodec(info, type)
                type.startsWith("audio/") -> audio += caps.toAudioCodec(info, type)
            }
        }
    }
    return DeviceCapabilities(video = video, audio = audio, deviceSummary = androidDeviceSummary())
}

private fun androidDeviceSummary(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

private fun CodecCapabilities.toVideoCodec(info: MediaCodecInfo, mime: String): CodecSupport.Video {
    val role = if (info.isEncoder) CodecSupport.Role.Encoder else CodecSupport.Role.Decoder
    val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else false
    val profiles = readProfiles(mime)
    val supports10Bit = has10BitSupport(mime)
    val supportsHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        isFeatureSupported(CodecCapabilities.FEATURE_HdrEditing)
    } else {
        false
    }
    val vc = videoCapabilities
    // NOTE: supportedWidths.upper × supportedHeights.upper is a best-effort upper
    // bound — a codec that reports (maxW=4096, maxH=4096) may not actually decode
    // 4096×4096 (only 4096×2160). VideoCapabilities.isSizeSupported(w, h) returns
    // the precise answer per-combination; we surface the pair for display and rely
    // on the decoder to reject unsupported combinations at runtime via a typed
    // VideoCompressionError. Supportability's resolution check is advisory for
    // this reason.
    return CodecSupport.Video(
        mimeType = mime,
        role = role,
        codecName = info.name,
        hardwareAccelerated = hardware,
        profiles = profiles,
        maxBitrate = vc?.bitrateRange?.upper?.toLong(),
        maxResolution = vc?.let { it.supportedWidths.upper to it.supportedHeights.upper },
        maxFrameRate = vc?.supportedFrameRates?.upper,
        supports10Bit = supports10Bit,
        supportsHdr = supportsHdr,
    )
}

private fun CodecCapabilities.toAudioCodec(info: MediaCodecInfo, mime: String): CodecSupport.Audio {
    val role = if (info.isEncoder) CodecSupport.Role.Encoder else CodecSupport.Role.Decoder
    val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else false
    val profiles = readProfiles(mime)
    val ac = audioCapabilities
    return CodecSupport.Audio(
        mimeType = mime,
        role = role,
        codecName = info.name,
        hardwareAccelerated = hardware,
        profiles = profiles,
        maxBitrate = ac?.bitrateRange?.upper?.toLong(),
        sampleRates = ac?.supportedSampleRates?.toList().orEmpty(),
        maxChannels = ac?.maxInputChannelCount,
    )
}

private fun CodecCapabilities.readProfiles(mime: String): List<String> =
    profileLevels.orEmpty()
        .map { humanProfileName(mime, it.profile) }
        .distinct()
        .filter { it.isNotEmpty() }

private fun CodecCapabilities.has10BitSupport(mime: String): Boolean =
    profileLevels.orEmpty().any { isTenBitProfile(mime, it.profile) } ||
        (colorFormats ?: intArrayOf()).any { it == COLOR_FORMAT_YUVP010 }

// MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (API 29+).
private const val COLOR_FORMAT_YUVP010 = 54
