package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult

/** Compresses image files. */
interface ImageCompressor {

    /**
     * Compress an image file.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * @param inputPath Absolute filesystem path to the source image.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (format, quality, resize).
     * @param onProgress Called with a value between 0.0 and 1.0 as compression progresses.
     * @return [Result] wrapping [CompressionResult] on success, or an exception on failure.
     */
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig = ImageCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
