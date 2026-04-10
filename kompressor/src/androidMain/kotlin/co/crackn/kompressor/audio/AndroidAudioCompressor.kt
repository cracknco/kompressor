package co.crackn.kompressor.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.suspendRunCatching
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Android audio compressor backed by [MediaCodec] and [MediaMuxer]. */
internal class AndroidAudioCompressor : AudioCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        val startNanos = System.nanoTime()
        onProgress(0f)
        val inputSize = File(inputPath).length()
        val pipeline = AudioPipeline(inputPath, outputPath, config)
        pipeline.execute(onProgress)
        onProgress(1f)
        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Encapsulates the MediaCodec decode→encode→mux pipeline for a single
 * audio transcode operation.
 */
@Suppress("TooManyFunctions")
private class AudioPipeline(
    inputPath: String,
    private val outputPath: String,
    config: AudioCompressionConfig,
) {
    private val channelCount =
        if (config.channels == AudioChannels.MONO) 1 else 2

    private val extractor = MediaExtractor().apply { setDataSource(inputPath) }

    private val outputFormat: MediaFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        config.sampleRate,
        channelCount,
    ).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        )
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
    }

    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        try {
            val (trackIndex, trackFormat) = findAudioTrack()
            extractor.selectTrack(trackIndex)
            val totalDurationUs = trackFormat.safeLong(MediaFormat.KEY_DURATION)
            onProgress(PROGRESS_SETUP)
            currentCoroutineContext().ensureActive()

            val inputMime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Input track has no MIME type")
            transcodeTrack(inputMime, trackFormat, totalDurationUs, onProgress)
        } finally {
            extractor.release()
        }
    }

    private suspend fun transcodeTrack(
        inputMime: String,
        inputFormat: MediaFormat,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val decoder = MediaCodec.createDecoderByType(inputMime)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            decoder.configure(inputFormat, null, null, 0)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            decoder.start()
            encoder.start()
            muxEncodedOutput(decoder, encoder, totalDurationUs, onProgress)
        } finally {
            decoder.safeRelease()
            encoder.safeRelease()
        }
    }

    @Suppress("NestedBlockDepth")
    private suspend fun muxEncodedOutput(
        decoder: MediaCodec,
        encoder: MediaCodec,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val sink = EncoderSink(outputPath)
        try {
            val info = MediaCodec.BufferInfo()
            val state = PipelineState()
            while (!state.encoderDone) {
                currentCoroutineContext().ensureActive()
                if (!state.extractorDone) state.extractorDone = feedDecoder(decoder)
                if (!state.decoderDone) state.decoderDone = drainDecoder(decoder, encoder, info)
                sink.drainEncoder(encoder, info, totalDurationUs, onProgress)
                state.encoderDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            }
        } finally {
            sink.release()
        }
    }

    private fun feedDecoder(decoder: MediaCodec): Boolean {
        val idx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (idx < 0) return false
        val buf = decoder.getInputBuffer(idx) ?: error("Decoder input buffer is null")
        val size = extractor.readSampleData(buf, 0)
        val isEos = size < 0
        if (isEos) {
            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
        return isEos
    }

    @Suppress("ReturnCount")
    private fun drainDecoder(
        decoder: MediaCodec,
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
    ): Boolean {
        val status = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
        if (status < 0) return false
        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        if (info.size > 0) copyDecodedToEncoder(decoder, encoder, status, info)
        decoder.releaseOutputBuffer(status, false)
        if (isEos) {
            val encIdx = spinForInputBuffer(encoder)
            encoder.queueInputBuffer(encIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        return isEos
    }

    private fun copyDecodedToEncoder(
        decoder: MediaCodec,
        encoder: MediaCodec,
        decoderIdx: Int,
        info: MediaCodec.BufferInfo,
    ) {
        val decoded = decoder.getOutputBuffer(decoderIdx) ?: error("Decoder output null")
        val encIdx = spinForInputBuffer(encoder)
        val encBuf = encoder.getInputBuffer(encIdx) ?: error("Encoder input null")
        encBuf.clear()
        val bytes = minOf(decoded.remaining(), encBuf.capacity())
        val slice = decoded.slice().apply { limit(bytes) }
        encBuf.put(slice)
        encoder.queueInputBuffer(encIdx, 0, bytes, info.presentationTimeUs, 0)
    }

    private fun findAudioTrack(): Pair<Int, MediaFormat> {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to format
        }
        throw IllegalArgumentException("No audio track found in input file")
    }

    private fun spinForInputBuffer(codec: MediaCodec): Int {
        while (true) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) return index
        }
    }

    private fun MediaFormat.safeLong(key: String): Long =
        try { getLong(key) } catch (_: Exception) { 0L }

    private fun MediaCodec.safeRelease() {
        try { stop() } catch (_: IllegalStateException) { /* may not be started */ }
        release()
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_INPUT_SIZE = 16_384
        const val PROGRESS_SETUP = 0.05f
        const val PROGRESS_TRANSCODE_RANGE = 0.90f
    }
}

private class PipelineState(
    var extractorDone: Boolean = false,
    var decoderDone: Boolean = false,
    var encoderDone: Boolean = false,
)

private class EncoderSink(outputPath: String) {
    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex = -1
    private var started = false

    suspend fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val status = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            trackIndex = muxer.addTrack(encoder.outputFormat)
            muxer.start()
            started = true
            return
        }
        if (status < 0) return
        val buf = encoder.getOutputBuffer(status) ?: error("Encoder output null")
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
        if (info.size > 0 && started) muxer.writeSampleData(trackIndex, buf, info)
        encoder.releaseOutputBuffer(status, false)
        if (totalDurationUs > 0 && info.presentationTimeUs > 0) {
            val fraction = (info.presentationTimeUs.toFloat() / totalDurationUs).coerceAtMost(1f)
            onProgress(PROGRESS_SETUP + PROGRESS_TRANSCODE_RANGE * fraction)
        }
    }

    fun release() {
        if (started) {
            try { muxer.stop() } catch (_: IllegalStateException) { /* may not be started */ }
        }
        muxer.release()
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val PROGRESS_SETUP = 0.05f
        const val PROGRESS_TRANSCODE_RANGE = 0.90f
    }
}
