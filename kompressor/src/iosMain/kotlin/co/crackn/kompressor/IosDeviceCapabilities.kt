package co.crackn.kompressor

import platform.UIKit.UIDevice

/**
 * iOS capability matrix. AVFoundation on iOS 15+ guarantees H.264 (Baseline/Main/High)
 * and HEVC Main for both decode and encode; HEVC Main 10 decode is guaranteed on
 * A10 Fusion (iPhone 7, 2016) and later, encode on A11 (iPhone 8) and later. Because
 * those chips cover every device that runs iOS 15, we report Main 10 support
 * unconditionally. Extending this via `VTCopyVideoEncoderList` / `VTCopyVideoDecoderList`
 * would give per-codec detail on newer SoCs — out of scope for this pass.
 */
public actual fun queryDeviceCapabilities(): DeviceCapabilities {
    val device = UIDevice.currentDevice
    val summary = "${device.model} — ${device.systemName} ${device.systemVersion}"

    val hevcProfiles = listOf("Main", "Main 10")
    val h264Profiles = listOf("Baseline", "Main", "High")

    val decoders = listOf(
        videoCodec("video/avc", CodecSupport.Role.Decoder, h264Profiles, supports10Bit = true),
        videoCodec("video/hevc", CodecSupport.Role.Decoder, hevcProfiles, supports10Bit = true, supportsHdr = true),
    )
    val encoders = listOf(
        videoCodec("video/avc", CodecSupport.Role.Encoder, h264Profiles, supports10Bit = false),
        videoCodec("video/hevc", CodecSupport.Role.Encoder, hevcProfiles, supports10Bit = true, supportsHdr = true),
    )
    val audioCodecs = listOf(
        audioCodec("audio/mp4a-latm", CodecSupport.Role.Decoder),
        audioCodec("audio/mp4a-latm", CodecSupport.Role.Encoder),
        audioCodec("audio/mpeg", CodecSupport.Role.Decoder),
        audioCodec("audio/opus", CodecSupport.Role.Decoder),
    )

    return DeviceCapabilities(
        video = decoders + encoders,
        audio = audioCodecs,
        deviceSummary = summary,
    )
}

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
