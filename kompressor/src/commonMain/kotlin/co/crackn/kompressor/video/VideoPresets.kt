package co.crackn.kompressor.video

/** Ready-to-use [VideoCompressionConfig] presets for common use cases. */
object VideoPresets {

    /** Optimised for chat/messaging apps: 720p, 1.2 Mbps video, 128 kbps audio. */
    val MESSAGING = VideoCompressionConfig(
        videoBitrate = 1_200_000,
        audioBitrate = 128_000,
    )

    /** High quality: 1080p, 3.5 Mbps video, 192 kbps audio. */
    val HIGH_QUALITY = VideoCompressionConfig(
        maxResolution = MaxResolution.HD_1080,
        videoBitrate = 3_500_000,
        audioBitrate = 192_000,
    )

    /** Low bandwidth: 480p, 600 kbps video, 96 kbps audio, 24 fps, keyframe every 3s. */
    val LOW_BANDWIDTH = VideoCompressionConfig(
        maxResolution = MaxResolution.SD_480,
        videoBitrate = 600_000,
        audioBitrate = 96_000,
        maxFrameRate = 24,
        keyFrameInterval = 3,
    )

    /** Optimised for social media uploads: 720p, 2.0 Mbps video, 128 kbps audio, keyframe every 1s. */
    val SOCIAL_MEDIA = VideoCompressionConfig(
        videoBitrate = 2_000_000,
        audioBitrate = 128_000,
        keyFrameInterval = 1,
    )

    /**
     * HDR10 preservation: 1080p HEVC 10-bit BT.2020 + PQ, 10 Mbps video, 192 kbps audio.
     *
     * Requires a device whose `MediaCodecList` / AVFoundation exposes an HEVC Main10 HDR10
     * encoder. Callers can check via [co.crackn.kompressor.queryDeviceCapabilities] — the
     * compressor surfaces `VideoCompressionError.UnsupportedSourceFormat` if the selected
     * device cannot encode HDR10.
     */
    val HDR10_1080P = VideoCompressionConfig(
        codec = VideoCodec.HEVC,
        maxResolution = MaxResolution.HD_1080,
        videoBitrate = 10_000_000,
        audioBitrate = 192_000,
        dynamicRange = DynamicRange.HDR10,
    )
}
