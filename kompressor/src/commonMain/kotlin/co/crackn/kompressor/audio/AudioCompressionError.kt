package co.crackn.kompressor.audio

/**
 * Typed error hierarchy returned inside [Result.failure] by [AudioCompressor.compress].
 *
 * Library callers can `when`-branch on these subtypes to surface actionable, localized messages
 * instead of a generic "codec failed" string. Each error preserves the original platform [cause]
 * for diagnostics / reporting.
 *
 * The hierarchy mirrors `co.crackn.kompressor.video.VideoCompressionError` — the Android
 * implementations share the underlying Media3 error-band classification, and callers that handle
 * both media types can follow the same `when` structure.
 */
public sealed class AudioCompressionError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * No decoder on this device supports the source file's codec / profile, or the container
     * format is not recognised. Irrecoverable on-device — users should convert the file first
     * (e.g. to AAC / MP3 / WAV).
     */
    public class UnsupportedSourceFormat(
        /** Free-form diagnostic — codec and source bitrate / sample rate when known. */
        public val details: String,
        cause: Throwable? = null,
    ) : AudioCompressionError("Unsupported source format: $details", cause)

    /**
     * A decoder was found and initialised but failed while decoding the stream (corrupt file,
     * OEM codec bug mid-stream, unexpected end of stream).
     */
    public class DecodingFailed(
        /** Free-form diagnostic from the decoder. */
        public val details: String,
        cause: Throwable? = null,
    ) : AudioCompressionError("Decoding failed: $details", cause)

    /**
     * Encoding the output failed (no AAC encoder, out of memory, muxer refused a sample,
     * audio-processor reconfiguration error, etc.).
     */
    public class EncodingFailed(
        /** Free-form diagnostic from the encoder / muxer / audio processor. */
        public val details: String,
        cause: Throwable? = null,
    ) : AudioCompressionError("Encoding failed: $details", cause)

    /**
     * I/O failure reading the input file or writing the output (permission denied, disk full,
     * `content://` URI backed by a revoked provider, etc.).
     */
    public class IoFailed(
        /** Free-form diagnostic from the platform I/O layer. */
        public val details: String,
        cause: Throwable? = null,
    ) : AudioCompressionError("IO failed: $details", cause)

    /** Fallback for platform errors we couldn't classify. */
    public class Unknown(
        /** Free-form diagnostic — usually the original platform error message. */
        public val details: String,
        cause: Throwable? = null,
    ) : AudioCompressionError("Compression failed: $details", cause)
}
