/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

/**
 * Typed error hierarchy returned inside [Result.failure] by [ImageCompressor.compress].
 *
 * Callers can `when`-branch on these subtypes to surface actionable, localized messages rather
 * than a generic string. Each error preserves the original platform [cause] for diagnostics.
 *
 * Mirrors [co.crackn.kompressor.audio.AudioCompressionError] and
 * [co.crackn.kompressor.video.VideoCompressionError] so callers that handle multiple media types
 * can use the same branching structure.
 */
public sealed class ImageCompressionError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /**
     * The source file's container / codec is not recognised by the platform image decoder
     * (e.g. a random-bytes file passed through an image compressor). Irrecoverable on-device.
     */
    public class UnsupportedSourceFormat(
        /** Free-form diagnostic — source path / format detail when known. */
        public val details: String,
        cause: Throwable? = null,
    ) : ImageCompressionError("Unsupported source format: $details", cause)

    /**
     * The platform decoder was invoked but failed to produce a bitmap (truncated JPEG, corrupt
     * PNG, unsupported bit depth / color space for this OEM, etc.).
     */
    public class DecodingFailed(
        /** Free-form diagnostic — decoder error detail when available. */
        public val details: String,
        cause: Throwable? = null,
    ) : ImageCompressionError("Decoding failed: $details", cause)

    /** Encoding the output JPEG/PNG failed (OOM, bitmap.compress returned false, etc.). */
    public class EncodingFailed(
        /** Free-form diagnostic. */
        public val details: String,
        cause: Throwable? = null,
    ) : ImageCompressionError("Encoding failed: $details", cause)

    /**
     * I/O failure reading the input or writing the output (missing file, permission denied,
     * disk full, revoked `content://` URI, etc.).
     */
    public class IoFailed(
        /** Free-form diagnostic. */
        public val details: String,
        cause: Throwable? = null,
    ) : ImageCompressionError("IO failed: $details", cause)

    /** Fallback for platform errors we couldn't classify. */
    public class Unknown(
        /** Free-form diagnostic — usually the original platform error message. */
        public val details: String,
        cause: Throwable? = null,
    ) : ImageCompressionError("Image compression failed: $details", cause)
}
