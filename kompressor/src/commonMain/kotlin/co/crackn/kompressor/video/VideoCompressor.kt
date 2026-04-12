package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult

/**
 * Compresses video files.
 *
 * **Rotation note:** the current implementation does not read or preserve the
 * source video's rotation metadata (`preferredTransform` on iOS,
 * `KEY_ROTATION` on Android). Portrait-recorded videos may appear rotated
 * in the output. This is a known v1 limitation.
 */
public interface VideoCompressor {

    /**
     * Video codec MIME types this device can decode from. The set reflects the
     * platform's reported decoder capabilities at the moment of the first read
     * and is stable for the lifetime of this instance; a missing entry does not
     * guarantee the decoder can't be loaded at runtime, it just means the
     * implementation didn't detect it.
     *
     * Defaults to an empty set for forward compatibility; implementations
     * override to declare what they actually support.
     */
    public val supportedInputFormats: Set<String> get() = emptySet()

    /**
     * Video codec MIME types this device can encode to. See
     * [supportedInputFormats] for the semantics.
     */
    public val supportedOutputFormats: Set<String> get() = emptySet()

    /**
     * Compress a video file.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * Failures surface as [VideoCompressionError] subtypes wrapped in [Result.failure]:
     * callers can `when`-branch on the concrete type to show actionable UI.
     *
     * @param inputPath Absolute filesystem path to the source video.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (codec, resolution, bitrate, etc.).
     * @param onProgress Called with a value between 0.0 and 1.0 as compression progresses.
     * @return [Result] wrapping [CompressionResult] on success, or a [VideoCompressionError] on failure.
     */
    public suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig = VideoCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
