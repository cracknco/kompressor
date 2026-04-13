package co.crackn.kompressor

import platform.UIKit.UIDevice

/**
 * iOS capability matrix. AVFoundation on iOS 15+ guarantees H.264 (Baseline/Main/High)
 * and HEVC Main for both decode and encode.
 *
 * HEVC Main 10 decode requires A9 (iPhone 6s) — guaranteed on every iOS 15 device.
 * HEVC Main 10 **encode** requires A10 Fusion (iPhone 7, 2016); an iOS 15 device on
 * A9 silicon will accept the settings dictionary but fail during the writer's session.
 * We advertise the HEVC encoder as `supports10Bit = true, supportsHdr = true` here
 * because the compressor gates the actual compress call on a runtime probe
 * (`IosVideoCompressor.requireHdr10HevcCapability` uses `AVAssetWriter.
 * canApplyOutputSettings` with BT.2020+PQ) and turns any unsupported device into a
 * typed [co.crackn.kompressor.video.VideoCompressionError.UnsupportedSourceFormat]
 * *before* the writer opens, so the aspirational capability flag never leaks into a
 * silent downgrade or a post-start crash.
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
    // A10 Fusion (2016) is the first iOS SoC with hardware HEVC Main10 encode; the runtime
    // pre-flight (`IosVideoCompressor.requireHdr10HevcCapability`) surfaces a typed error on
    // A9/iOS 15 devices that can't actually honour the advertised profile, so this entry is
    // allowed to read aspirationally.
    val hevcEncoderSupports10Bit = true
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
            supports10Bit = hevcEncoderSupports10Bit,
            supportsHdr = hevcEncoderSupports10Bit,
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
