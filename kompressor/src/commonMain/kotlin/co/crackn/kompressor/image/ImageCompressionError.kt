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

    /** Holds module-wide sentinel constants for [ImageCompressionError] subtypes. */
    public companion object {
        /**
         * Sentinel for [UnsupportedInputFormat.minApi] / [UnsupportedOutputFormat.minApi] meaning
         * "not implemented on this platform at any version" (e.g. HEIC output on Android, WEBP
         * output on iOS). When this value is returned, the error message omits the
         * "(requires $platform $minApi+)" clause — callers rendering localised text should branch
         * on `minApi == NOT_IMPLEMENTED` before formatting a "Supported from … $minApi" string.
         */
        public const val NOT_IMPLEMENTED: Int = Int.MAX_VALUE
    }

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
     * The source file's container is recognised but the current platform version cannot decode it.
     *
     * Carries the minimum platform version required so callers can:
     *  1. show a localised "requires Android 12+ / iOS 16+" message, or
     *  2. re-encode the input on a compatible device before retrying.
     *
     * @property format Lower-case format identifier (e.g. `"heic"`, `"avif"`).
     * @property platform Name of the platform whose version gate failed (`"android"` or `"ios"`).
     * @property minApi Minimum platform version that can decode this input. For Android this is
     *   the API level (e.g. `30`); for iOS this is the major iOS version (e.g. `16`). Equal to
     *   [NOT_IMPLEMENTED] when the format is never decodable on [platform] in this release.
     */
    public class UnsupportedInputFormat(
        public val format: String,
        public val platform: String,
        public val minApi: Int,
        cause: Throwable? = null,
    ) : ImageCompressionError(
        buildVersionGatedMessage(kind = "input", format = format, platform = platform, minApi = minApi),
        cause,
    ) {
        /** `true` when this format is not supported on [platform] at any version. */
        public val isNotImplementedOnPlatform: Boolean
            get() = minApi == NOT_IMPLEMENTED
    }

    /**
     * The requested output format cannot be produced on the current platform version.
     *
     * Carries the minimum platform version required so callers can catch this error, fall back to
     * a widely-supported format (JPEG / WebP / PNG), and retry.
     *
     * @property format Lower-case format identifier (e.g. `"avif"`, `"heic"`).
     * @property platform Name of the platform whose version gate failed (`"android"` or `"ios"`).
     * @property minApi Minimum platform version that can encode this output. See
     *   [UnsupportedInputFormat.minApi] for the meaning per platform. Equal to [NOT_IMPLEMENTED]
     *   when the format is never encodable on [platform] in this release.
     */
    public class UnsupportedOutputFormat(
        public val format: String,
        public val platform: String,
        public val minApi: Int,
        cause: Throwable? = null,
    ) : ImageCompressionError(
        buildVersionGatedMessage(kind = "output", format = format, platform = platform, minApi = minApi),
        cause,
    ) {
        /** `true` when this format is not supported on [platform] at any version. */
        public val isNotImplementedOnPlatform: Boolean
            get() = minApi == NOT_IMPLEMENTED
    }

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

/**
 * Builds the human-readable message for [ImageCompressionError.UnsupportedInputFormat] /
 * [ImageCompressionError.UnsupportedOutputFormat]. When [minApi] equals
 * [ImageCompressionError.NOT_IMPLEMENTED] the "(requires $platform $minApi+)" suffix is omitted
 * so the text reads naturally ("not implemented on this platform" rather than "requires android
 * 2147483647+"). Kept file-scope so the sealed class body stays readable.
 */
private fun buildVersionGatedMessage(
    kind: String,
    format: String,
    platform: String,
    minApi: Int,
): String = if (minApi == ImageCompressionError.NOT_IMPLEMENTED) {
    "Unsupported $kind format '$format' on $platform: not implemented on this platform"
} else {
    "Unsupported $kind format '$format' on this $platform version (requires $platform $minApi+)"
}
