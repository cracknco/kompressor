package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult

internal class AndroidVideoCompressor : VideoCompressor {
    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> {
        TODO("Not yet implemented")
    }
}
