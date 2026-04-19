/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

/**
 * Typed error hierarchy returned inside [Result.failure] by [VideoCompressor.compress].
 *
 * Library callers can `when`-branch on these subtypes to surface actionable,
 * localized messages instead of a generic "codec failed" string. Each error
 * preserves the original platform [cause] for diagnostics/reporting.
 */
public sealed class VideoCompressionError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * No decoder on this device supports the source file's codec/profile/level
     * (e.g. HEVC Main 10 on a device that only ships HEVC Main), or the container
     * format is not recognised. Irrecoverable on-device — users should convert
     * the file first.
     */
    public class UnsupportedSourceFormat(
        /** Free-form diagnostic — codec/profile/level and source dimensions when known. */
        public val details: String,
        cause: Throwable? = null,
    ) : VideoCompressionError("Unsupported source format: $details", cause)

    /**
     * The device's HEVC encoder declared HDR10 Main10 support via its capability matrix
     * but the runtime probe (actually allocating the encoder with 10-bit P010 input)
     * failed — an OEM firmware bug where `MediaCodecList` over-advertises.
     *
     * Raised exclusively on Android, only when the caller opts into HDR10 via
     * [co.crackn.kompressor.video.DynamicRange.HDR10]. Callers should offer an SDR
     * fallback or ask the user to transcode elsewhere; we deliberately do NOT silently
     * downgrade HDR10 → SDR (that would be a data-loss surprise).
     */
    public class Hdr10NotSupported(
        /** Device identifier — `Build.MODEL` on Android. */
        public val device: String,
        /** Codec name probed — e.g. `c2.qti.hevc.encoder` on Qualcomm, `OMX.google.hevc.encoder`. */
        public val codec: String,
        /** Human-readable reason the probe failed — typically the platform exception message. */
        public val reason: String,
        cause: Throwable? = null,
    ) : VideoCompressionError(
        "HEVC Main10 HDR10 not supported on this device: device=$device, codec=$codec, reason=$reason",
        cause,
    )

    /**
     * A decoder was found and initialised but failed while decoding the stream
     * (corrupt file, OEM codec bug mid-stream, unexpected end of stream).
     */
    public class DecodingFailed(
        /** Free-form diagnostic from the decoder. */
        public val details: String,
        cause: Throwable? = null,
    ) : VideoCompressionError("Decoding failed: $details", cause)

    /**
     * Encoding the output failed (no H.264 encoder, out of memory,
     * muxer refused a sample, etc.).
     */
    public class EncodingFailed(
        /** Free-form diagnostic from the encoder/muxer. */
        public val details: String,
        cause: Throwable? = null,
    ) : VideoCompressionError("Encoding failed: $details", cause)

    /**
     * I/O failure reading the input file or writing the output (permission
     * denied, disk full, network-backed URI failed, etc.).
     */
    public class IoFailed(
        /** Free-form diagnostic from the platform I/O layer. */
        public val details: String,
        cause: Throwable? = null,
    ) : VideoCompressionError("IO failed: $details", cause)

    /** Fallback for platform errors we couldn't classify. */
    public class Unknown(
        /** Free-form diagnostic — usually the original platform error message. */
        public val details: String,
        cause: Throwable? = null,
    ) : VideoCompressionError("Compression failed: $details", cause)
}
