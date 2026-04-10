package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult

/** Compresses video files. */
interface VideoCompressor {

    /**
     * Compress a video file.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * @param inputPath Absolute filesystem path to the source video.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (codec, resolution, bitrate, etc.).
     * @param onProgress Called with a value between 0.0 and 1.0 as compression progresses.
     * @return [Result] wrapping [CompressionResult] on success, or an exception on failure.
     */
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig = VideoCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
