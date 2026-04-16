/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

/**
 * Describes one codec entry (decoder or encoder) available on the device.
 *
 * Built from platform capability queries — `MediaCodecList` on Android,
 * VideoToolbox + AVFoundation on iOS.
 */
public data class CodecSupport(
    /** MIME type, e.g. `video/hevc`, `audio/mp4a-latm`. */
    public val mimeType: String,
    /** Whether this describes a decoder or an encoder. */
    public val role: Role,
    /** Platform codec name (e.g. `c2.exynos.hevc.decoder`) when exposed, else null. */
    public val codecName: String? = null,
    /** Vendor-reported hardware acceleration. */
    public val hardwareAccelerated: Boolean = false,
    /** Supported profile names (e.g. `["Main", "Main 10"]`). */
    public val profiles: List<String> = emptyList(),
    /** Max width/height supported (video codecs only). */
    public val maxResolution: Pair<Int, Int>? = null,
    /** Max frame rate at [maxResolution] (video codecs only). */
    public val maxFrameRate: Int? = null,
    /** Max bitrate supported in bits per second. */
    public val maxBitrate: Long? = null,
    /** Whether 10-bit color is supported. */
    public val supports10Bit: Boolean = false,
    /** Whether HDR editing/decoding is supported. */
    public val supportsHdr: Boolean = false,
    /** Supported audio sample rates (audio codecs only). */
    public val audioSampleRates: List<Int> = emptyList(),
    /** Supported audio channel counts (audio codecs only). */
    public val audioMaxChannels: Int? = null,
) {
    /** Whether this codec reads a format ([Decoder]) or writes one ([Encoder]). */
    public enum class Role {
        /** Reads (decodes) a format. */
        Decoder,

        /** Writes (encodes) a format. */
        Encoder,
    }
}

/**
 * Aggregate view of the device's video/audio codec capabilities.
 */
public data class DeviceCapabilities(
    /** Video codec entries (both decoders and encoders). */
    public val video: List<CodecSupport>,
    /** Audio codec entries (both decoders and encoders). */
    public val audio: List<CodecSupport>,
    /** Free-form device description (model, OS version). */
    public val deviceSummary: String,
)

/**
 * Queries the running device for its full codec capability matrix.
 *
 * Expensive enough to cache — the Android implementation iterates
 * `MediaCodecList.REGULAR_CODECS`. Call once per process.
 */
public expect fun queryDeviceCapabilities(): DeviceCapabilities
