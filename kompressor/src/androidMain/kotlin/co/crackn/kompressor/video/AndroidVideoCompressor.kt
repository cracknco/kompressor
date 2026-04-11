package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.suspendRunCatching
import java.io.File

/** Android video compressor backed by [android.media.MediaCodec] and [android.media.MediaMuxer]. */
internal class AndroidVideoCompressor : VideoCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        val startNanos = System.nanoTime()
        onProgress(0f)
        val inputSize = File(inputPath).length()

        VideoTranscoder(inputPath, outputPath, config).transcode(onProgress)

        onProgress(1f)
        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
