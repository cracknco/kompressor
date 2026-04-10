package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult

internal class IosImageCompressor : ImageCompressor {
    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> {
        TODO("Not yet implemented")
    }
}
