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
     * @return [Result] wrapping [CompressionResult] on success, or an [ImageCompressionError]
     *   subtype on failure. Possible failure types:
     *   [ImageCompressionError.UnsupportedSourceFormat],
     *   [ImageCompressionError.DecodingFailed],
     *   [ImageCompressionError.EncodingFailed],
     *   [ImageCompressionError.IoFailed],
     *   [ImageCompressionError.Unknown]. [IllegalArgumentException] may still be thrown
     *   synchronously for programmer-error configs (e.g. requesting a non-JPEG output format).
     *
     * Implementations are stateless. Concurrent `compress()` calls from different coroutines on
     * the same instance are safe provided output paths differ. Concurrent calls with the same
     * output path produce undefined results.
     */
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig = ImageCompressionConfig(),
    ): Result<CompressionResult>
}
