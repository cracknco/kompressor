package co.crackn.kompressor

import platform.UIKit.UIDevice

/**
 * iOS capability matrix. AVFoundation on iOS 15+ guarantees H.264 (Baseline/Main/High)
 * and HEVC Main for both decode and encode.
 *
 * HEVC Main 10 decode requires A9 (iPhone 6s) — guaranteed on every iOS 15 device.
 * HEVC Main 10 **encode** requires A10 Fusion (iPhone 7, 2016). The iPhone 6s /
 * 6s Plus (A9) run iOS 15 but cannot encode 10-bit HEVC, so we conservatively
 * advertise the HEVC encoder as 8-bit only. A per-chip detection via
 * `VTCopyVideoEncoderList` would lift the restriction on newer SoCs — left as a
 * follow-up because the observable false-negative on A10+ is "we ask Media3 to
 * transcode Main 10 HEVC down to 8-bit" which is correct behaviour anyway.
 *
 * H.264 has no 10-bit profile on iOS hardware, so every AVC entry is
 * `supports10Bit = false`.
 */
public actual fun queryDeviceCapabilities(): DeviceCapabilities {
    val device = UIDevice.currentDevice
    val summary = "${device.model} — ${device.systemName} ${device.systemVersion}"
    return DeviceCapabilities(
        video = iosVideoCodecs(),
        audio = iosAudioCodecs(),
        deviceSummary = summary,
    )
}

private fun iosVideoCodecs(): List<CodecSupport> {
    val hevcDecoderProfiles = listOf("Main", "Main 10")
    val hevcEncoderProfiles = listOf("Main")
    val h264Profiles = listOf("Baseline", "Main", "High")
    return listOf(
        videoCodec("video/avc", CodecSupport.Role.Decoder, h264Profiles, supports10Bit = false),
        videoCodec(
            "video/hevc",
            CodecSupport.Role.Decoder,
            hevcDecoderProfiles,
            supports10Bit = true,
            supportsHdr = true,
        ),
        videoCodec("video/avc", CodecSupport.Role.Encoder, h264Profiles, supports10Bit = false),
        videoCodec(
            "video/hevc",
            CodecSupport.Role.Encoder,
            hevcEncoderProfiles,
            supports10Bit = false,
            supportsHdr = false,
        ),
    )
}

private fun iosAudioCodecs(): List<CodecSupport> = listOf(
    audioCodec("audio/mp4a-latm", CodecSupport.Role.Decoder),
    audioCodec("audio/mp4a-latm", CodecSupport.Role.Encoder),
    audioCodec("audio/mpeg", CodecSupport.Role.Decoder),
    audioCodec("audio/opus", CodecSupport.Role.Decoder),
)

private fun videoCodec(
    mime: String,
    role: CodecSupport.Role,
    profiles: List<String>,
    supports10Bit: Boolean = false,
    supportsHdr: Boolean = false,
): CodecSupport = CodecSupport(
    mimeType = mime,
    role = role,
    hardwareAccelerated = true,
    profiles = profiles,
    supports10Bit = supports10Bit,
    supportsHdr = supportsHdr,
)

private fun audioCodec(mime: String, role: CodecSupport.Role): CodecSupport =
    CodecSupport(mimeType = mime, role = role, hardwareAccelerated = false)
