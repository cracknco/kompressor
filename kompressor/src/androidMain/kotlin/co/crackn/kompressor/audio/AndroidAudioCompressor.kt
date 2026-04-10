package co.crackn.kompressor.audio

import co.crackn.kompressor.CompressionResult

internal class AndroidAudioCompressor : AudioCompressor {
    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> {
        TODO("Not yet implemented")
    }
}
