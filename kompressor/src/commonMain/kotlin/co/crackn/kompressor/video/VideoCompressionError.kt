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
