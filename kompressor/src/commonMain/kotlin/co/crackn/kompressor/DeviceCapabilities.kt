/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

/**
 * A codec entry (decoder or encoder) available on the device.
 *
 * Split into [Video] and [Audio] variants so the fields that only apply to one media type (e.g.
 * [Video.maxResolution], [Audio.sampleRates]) aren't offered as `null`-by-default pollution on the
 * other. Consumers access the properties common to both via this sealed interface; media-specific
 * fields require smart-casting to the concrete variant.
 *
 * Built from platform capability queries — `MediaCodecList` on Android, VideoToolbox +
 * AVFoundation on iOS.
 */
public sealed interface CodecSupport {
    /** MIME type, e.g. `video/hevc`, `audio/mp4a-latm`. */
    public val mimeType: String

    /** Whether this describes a decoder or an encoder. */
    public val role: Role

    /** Platform codec name (e.g. `c2.exynos.hevc.decoder`) when exposed, else `null`. */
    public val codecName: String?

    /** Vendor-reported hardware acceleration. */
    public val hardwareAccelerated: Boolean

    /** Supported profile names (e.g. `["Main", "Main 10"]`). */
    public val profiles: List<String>

    /** Max bitrate supported in bits per second, when reported by the platform. */
    public val maxBitrate: Long?

    /** Whether this codec reads a format ([Decoder]) or writes one ([Encoder]). */
    public enum class Role {
        /** Reads (decodes) a format. */
        Decoder,

        /** Writes (encodes) a format. */
        Encoder,
    }

    /** Video codec entry. */
    public data class Video(
        override val mimeType: String,
        override val role: Role,
        override val codecName: String? = null,
        override val hardwareAccelerated: Boolean = false,
        override val profiles: List<String> = emptyList(),
        override val maxBitrate: Long? = null,
        /** Max width/height the codec reports as supported, or `null` when the platform didn't expose the pair. */
        public val maxResolution: Pair<Int, Int>? = null,
        /** Max frame rate at [maxResolution], or `null` when the platform didn't expose it. */
        public val maxFrameRate: Int? = null,
        /** Whether 10-bit colour is supported. */
        public val supports10Bit: Boolean = false,
        /** Whether HDR editing/decoding is supported. */
        public val supportsHdr: Boolean = false,
    ) : CodecSupport

    /** Audio codec entry. */
    public data class Audio(
        override val mimeType: String,
        override val role: Role,
        override val codecName: String? = null,
        override val hardwareAccelerated: Boolean = false,
        override val profiles: List<String> = emptyList(),
        override val maxBitrate: Long? = null,
        /** Supported sample rates in Hz. Empty when the platform didn't enumerate them. */
        public val sampleRates: List<Int> = emptyList(),
        /** Maximum channel count the encoder/decoder accepts, or `null` when not reported. */
        public val maxChannels: Int? = null,
    ) : CodecSupport
}

/**
 * Aggregate view of the device's video/audio codec capabilities.
 */
public data class DeviceCapabilities(
    /** Video codec entries (both decoders and encoders). */
    public val video: List<CodecSupport.Video>,
    /** Audio codec entries (both decoders and encoders). */
    public val audio: List<CodecSupport.Audio>,
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
