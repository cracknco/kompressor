package co.crackn.kompressor.audio

import co.crackn.kompressor.CompressionResult

/** Compresses audio files. */
interface AudioCompressor {

    /**
     * Compress an audio file.
     *
     * @param inputPath Absolute filesystem path to the source audio.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (codec, bitrate, sample rate, channels).
     * @param onProgress Called with a value between 0.0 and 1.0 as compression progresses.
     * @return [Result] wrapping [CompressionResult] on success, or an exception on failure.
     */
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig = AudioCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
