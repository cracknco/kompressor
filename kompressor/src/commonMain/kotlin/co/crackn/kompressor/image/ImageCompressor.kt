package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult

/** Compresses image files. */
interface ImageCompressor {

    /**
     * Compress an image file.
     *
     * Unlike audio/video compression, image compression has no progress callback because the
     * underlying platform APIs are synchronous single-step operations with no intermediate state.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * @param inputPath Absolute filesystem path to the source image.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (format, quality, resize).
     * @return [Result] wrapping [CompressionResult] on success, or an exception on failure.
     */
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig = ImageCompressionConfig(),
    ): Result<CompressionResult>
}
