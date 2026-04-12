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
     * Video codec MIME types for which this device has a decoder.
     * Queried once per process from the platform codec list.
     */
    public val supportedInputFormats: Set<String>

    /**
     * Video codec MIME types for which this device has an encoder.
     * Queried once per process from the platform codec list.
     */
    public val supportedOutputFormats: Set<String>

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
