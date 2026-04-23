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
    public data class UnsupportedSourceFormat(
        /** Free-form diagnostic — codec/profile/level and source dimensions when known. */
        public val details: String,
        override val cause: Throwable? = null,
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
    public data class Hdr10NotSupported(
        /** Device identifier — `Build.MODEL` on Android. */
        public val device: String,
        /** Codec name probed — e.g. `c2.qti.hevc.encoder` on Qualcomm, `OMX.google.hevc.encoder`. */
        public val codec: String,
        /** Human-readable reason the probe failed — typically the platform exception message. */
        public val reason: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError(
        "HEVC Main10 HDR10 not supported on this device: device=$device, codec=$codec, reason=$reason",
        cause,
    )

    /**
     * A decoder was found and initialised but failed while decoding the stream
     * (corrupt file, OEM codec bug mid-stream, unexpected end of stream).
     */
    public data class DecodingFailed(
        /** Free-form diagnostic from the decoder. */
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Decoding failed: $details", cause)

    /**
     * Encoding the output failed (no H.264 encoder, out of memory,
     * muxer refused a sample, etc.).
     */
    public data class EncodingFailed(
        /** Free-form diagnostic from the encoder/muxer. */
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Encoding failed: $details", cause)

    /**
     * I/O failure reading the input file or writing the output — permission denied, disk full,
     * network-backed URI failed, or a PhotoKit `PHAsset` that could not be resolved to an
     * `AVAsset` (transient iCloud download error, cancelled `requestAVAssetForVideo`, etc.).
     */
    public data class IoFailed(
        /** Free-form diagnostic from the platform I/O layer. */
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("IO failed: $details", cause)

    /** Fallback for platform errors we couldn't classify. */
    public data class Unknown(
        /** Free-form diagnostic — usually the original platform error message. */
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Compression failed: $details", cause)

    /**
     * Source media is inaccessible — invalid content URI, dead content provider, iCloud-offline
     * `PHAsset` with `allowNetworkAccess = false`, or a file deleted between probe and compress.
     *
     * Part of the [CRA-89](https://linear.app/crackn/issue/CRA-89) I/O refactor scaffolding:
     * emitted by platform source builders when the referenced resource is not reachable.
     *
     * @property details Free-form diagnostic — the source identifier (path, URI, asset id) and
     *   the platform-reported reason when known.
     */
    public data class SourceNotFound(
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Source not found: $details", cause)

    /**
     * The source stream threw on read. Includes `IOException` during okio [okio.Source]
     * consumption or `RemoteException` from a content provider.
     *
     * PhotoKit asset-resolution failures (iCloud download error, PHAsset corruption, etc.)
     * surface as [IoFailed], not this subtype — they happen during resolution, before any
     * stream is opened for reading.
     *
     * @property details Free-form diagnostic — the source identifier and the failing read offset
     *   or platform error message when known.
     */
    public data class SourceReadFailed(
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Source read failed: $details", cause)

    /**
     * The destination sink threw on write, or the destination file / URI could not be opened
     * for writing (missing `WRITE` permission, disk full before open, MediaStore provider
     * rejected the `INSERT`, SAF document permissions revoked).
     *
     * @property details Free-form diagnostic — the destination identifier and the platform
     *   error message when known.
     */
    public data class DestinationWriteFailed(
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Destination write failed: $details", cause)

    /**
     * Temp file creation or write failed — disk full, cache directory inaccessible, or `ENOSPC`
     * during chunked materialisation of a `Stream` / `Bytes` source. Generally retriable once
     * the user frees storage space.
     *
     * @property details Free-form diagnostic — the temp directory path, requested byte count,
     *   or underlying filesystem error when known.
     */
    public data class TempFileFailed(
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Temp file failed: $details", cause)

    /**
     * The requested `atMillis` offset for a video thumbnail / frame extraction exceeds the
     * source's duration. Distinct from [DecodingFailed] (codec issue on a valid frame request)
     * and [SourceNotFound] (source inaccessible) so callers can clamp the offset and retry
     * rather than treating the request as unrecoverable.
     *
     * Raised by [VideoCompressor.thumbnail] when the caller's `atMillis` strictly exceeds the
     * probed source duration. Callers should call the compressor's `probe`-equivalent metadata
     * read upfront (or read `AVURLAsset.duration` / `MediaMetadataRetriever.METADATA_KEY_DURATION`
     * directly) to clamp offsets into `[0, duration]` before invoking `thumbnail`.
     *
     * @property details Free-form diagnostic — typically `"atMillis=X > duration=Y"`.
     */
    public data class TimestampOutOfRange(
        public val details: String,
        override val cause: Throwable? = null,
    ) : VideoCompressionError("Timestamp out of range: $details", cause)
}
