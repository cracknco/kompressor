package co.crackn.kompressor.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.suspendRunCatching
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Android audio compressor backed by [MediaCodec] and [MediaMuxer]. */
internal class AndroidAudioCompressor : AudioCompressor {

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        require(config.codec == AudioCodec.AAC) { "Only AAC codec is currently supported" }
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

/** Shared constants for the transcode pipeline. */
private object PipelineConstants {
    const val CODEC_TIMEOUT_US = 0L
    const val MAX_INPUT_SIZE = 16_384
    const val REMUX_BUFFER_SIZE = 64 * 1024
    const val CHANNEL_CAPACITY = 8
    const val PROGRESS_SETUP = 0.05f
    const val PROGRESS_TRANSCODE_RANGE = 0.90f
    const val PROGRESS_REPORT_THRESHOLD = 0.01f
    const val BITRATE_TOLERANCE = 0.8f
}

/**
 * Orchestrates audio transcoding: decides between the remux fast-path
 * (AAC→AAC with matching params) and the full Channel-based
 * concurrent decode→encode pipeline.
 */
@Suppress("TooManyFunctions")
private class AudioPipeline(
    inputPath: String,
    private val outputPath: String,
    private val config: AudioCompressionConfig,
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
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PipelineConstants.MAX_INPUT_SIZE)
    }

    suspend fun execute(onProgress: suspend (Float) -> Unit) {
        try {
            val (trackIndex, trackFormat) = findAudioTrack()
            extractor.selectTrack(trackIndex)
            val totalDurationUs = trackFormat.safeLong(MediaFormat.KEY_DURATION)
            onProgress(PipelineConstants.PROGRESS_SETUP)
            currentCoroutineContext().ensureActive()

            if (canRemux(trackFormat)) {
                AudioRemuxer(extractor, outputPath).remux(totalDurationUs, onProgress)
            } else {
                val inputMime = trackFormat.getString(MediaFormat.KEY_MIME)
                    ?: error("Input track has no MIME type")
                transcodeTrack(inputMime, trackFormat, totalDurationUs, onProgress)
            }
        } finally {
            extractor.release()
        }
    }

    private fun canRemux(trackFormat: MediaFormat): Boolean {
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)
        val inputRate = trackFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE)
        val inputChannels = trackFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT)
        val inputBitrate = trackFormat.safeInt(MediaFormat.KEY_BIT_RATE)
        val threshold = (config.bitrate * PipelineConstants.BITRATE_TOLERANCE).toInt()
        val mimeMatches = mime == AAC_MIME
        val rateMatches = inputRate == 0 || inputRate == config.sampleRate
        val channelsMatch = inputChannels == 0 || inputChannels == channelCount
        val bitrateOk = inputBitrate <= 0 || inputBitrate >= threshold
        return mimeMatches && rateMatches && channelsMatch && bitrateOk
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
            runConcurrentPipeline(decoder, encoder, totalDurationUs, onProgress)
        } finally {
            decoder.safeRelease()
            encoder.safeRelease()
        }
    }

    private suspend fun runConcurrentPipeline(
        decoder: MediaCodec,
        encoder: MediaCodec,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val sink = EncoderSink(outputPath)
        try {
            coroutineScope {
                val pcmChannel = Channel<PcmChunk>(PipelineConstants.CHANNEL_CAPACITY)
                launch(Dispatchers.Default) { decodeToChannel(decoder, pcmChannel) }
                launch(Dispatchers.Default) {
                    encodeFromChannel(encoder, pcmChannel, sink, totalDurationUs, onProgress)
                }
            }
        } finally {
            sink.release()
        }
    }

    private suspend fun decodeToChannel(
        decoder: MediaCodec,
        channel: Channel<PcmChunk>,
    ) {
        val info = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        try {
            while (!decoderDone) {
                currentCoroutineContext().ensureActive()
                if (!extractorDone) extractorDone = feedDecoder(decoder)
                decoderDone = drainDecoderToChannel(decoder, info, channel)
                yield()
            }
        } finally {
            channel.close()
        }
    }

    private fun feedDecoder(decoder: MediaCodec): Boolean {
        val idx = decoder.dequeueInputBuffer(PipelineConstants.CODEC_TIMEOUT_US)
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

    private suspend fun drainDecoderToChannel(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        channel: Channel<PcmChunk>,
    ): Boolean {
        val status = decoder.dequeueOutputBuffer(info, PipelineConstants.CODEC_TIMEOUT_US)
        if (status < 0) return false
        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val chunk = copyToChunk(decoder, status, info, isEos)
        decoder.releaseOutputBuffer(status, false)
        channel.send(chunk)
        return isEos
    }

    private fun copyToChunk(
        decoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        isEos: Boolean,
    ): PcmChunk {
        val data = if (info.size > 0) {
            val output = decoder.getOutputBuffer(index) ?: error("Decoder output null")
            ByteArray(info.size).also { output.get(it) }
        } else {
            EMPTY_BYTES
        }
        return PcmChunk(data, info.size, info.presentationTimeUs, isEos)
    }

    private fun findAudioTrack(): Pair<Int, MediaFormat> {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to format
        }
        throw IllegalArgumentException("No audio track found in input file")
    }

    private fun MediaFormat.safeLong(key: String): Long =
        try { getLong(key) } catch (_: Exception) { 0L }

    private fun MediaFormat.safeInt(key: String): Int =
        try { getInteger(key) } catch (_: Exception) { 0 }

    private fun MediaCodec.safeRelease() {
        try { stop() } catch (_: IllegalStateException) { /* may not be started */ }
        release()
    }

    private companion object {
        const val AAC_MIME = "audio/mp4a-latm"
        val EMPTY_BYTES = ByteArray(0)
    }
}

/** Copies compressed AAC frames directly from extractor to muxer. */
private class AudioRemuxer(
    private val extractor: MediaExtractor,
    outputPath: String,
) {
    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    suspend fun remux(totalDurationUs: Long, onProgress: suspend (Float) -> Unit) {
        val format = extractor.getTrackFormat(extractor.sampleTrackIndex)
        val trackIndex = muxer.addTrack(format)
        muxer.start()
        try {
            copyFrames(trackIndex, totalDurationUs, onProgress)
        } finally {
            try { muxer.stop() } catch (_: IllegalStateException) { /* empty */ }
            muxer.release()
        }
    }

    private suspend fun copyFrames(
        trackIndex: Int,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val buffer = ByteBuffer.allocate(PipelineConstants.REMUX_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        var lastReported = PipelineConstants.PROGRESS_SETUP
        while (true) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(trackIndex, buffer, info)
            lastReported = reportProgress(totalDurationUs, lastReported, onProgress)
            extractor.advance()
            yield()
        }
    }

    private suspend fun reportProgress(
        totalDurationUs: Long,
        lastReported: Float,
        onProgress: suspend (Float) -> Unit,
    ): Float {
        if (totalDurationUs <= 0) return lastReported
        val fraction = (extractor.sampleTime.toFloat() / totalDurationUs).coerceAtMost(1f)
        val progress = PipelineConstants.PROGRESS_SETUP +
            PipelineConstants.PROGRESS_TRANSCODE_RANGE * fraction
        val shouldReport = progress - lastReported >= PipelineConstants.PROGRESS_REPORT_THRESHOLD
        if (shouldReport) onProgress(progress)
        return if (shouldReport) progress else lastReported
    }
}

/** Feeds PCM chunks from a Channel into a MediaCodec encoder, drains output to muxer. */
private suspend fun encodeFromChannel(
    encoder: MediaCodec,
    channel: Channel<PcmChunk>,
    sink: EncoderSink,
    totalDurationUs: Long,
    onProgress: suspend (Float) -> Unit,
) {
    for (chunk in channel) {
        currentCoroutineContext().ensureActive()
        if (chunk.size > 0) feedEncoderChunk(encoder, chunk)
        if (chunk.isEndOfStream) signalEncoderEos(encoder)
        sink.drainAllReady(encoder, totalDurationUs, onProgress)
    }
    while (!sink.drainAllReady(encoder, totalDurationUs, onProgress)) { yield() }
}

private suspend fun feedEncoderChunk(encoder: MediaCodec, chunk: PcmChunk) {
    val idx = awaitInputBuffer(encoder)
    val buf = encoder.getInputBuffer(idx) ?: error("Encoder input null")
    buf.clear()
    buf.put(chunk.data, 0, chunk.size)
    encoder.queueInputBuffer(idx, 0, chunk.size, chunk.presentationTimeUs, 0)
}

private suspend fun signalEncoderEos(encoder: MediaCodec) {
    val idx = awaitInputBuffer(encoder)
    encoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
}

private suspend fun awaitInputBuffer(codec: MediaCodec): Int {
    while (true) {
        currentCoroutineContext().ensureActive()
        val index = codec.dequeueInputBuffer(PipelineConstants.CODEC_TIMEOUT_US)
        if (index >= 0) return index
        yield()
    }
}

private class EncoderSink(outputPath: String) {
    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex = -1
    private var started = false
    private var lastReportedProgress = 0f

    suspend fun drainAllReady(
        encoder: MediaCodec,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val status = encoder.dequeueOutputBuffer(info, PipelineConstants.CODEC_TIMEOUT_US)
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                started = true
                continue
            }
            if (status < 0) return false
            writeAndReport(encoder, status, info, totalDurationUs, onProgress)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
    }

    private suspend fun writeAndReport(
        encoder: MediaCodec,
        status: Int,
        info: MediaCodec.BufferInfo,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val buf = encoder.getOutputBuffer(status) ?: error("Encoder output null")
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
        if (info.size > 0 && started) muxer.writeSampleData(trackIndex, buf, info)
        encoder.releaseOutputBuffer(status, false)
        if (totalDurationUs > 0 && info.presentationTimeUs > 0) {
            val fraction = (info.presentationTimeUs.toFloat() / totalDurationUs).coerceAtMost(1f)
            val progress = PipelineConstants.PROGRESS_SETUP +
                PipelineConstants.PROGRESS_TRANSCODE_RANGE * fraction
            if (progress - lastReportedProgress >= PipelineConstants.PROGRESS_REPORT_THRESHOLD) {
                lastReportedProgress = progress
                onProgress(progress)
            }
        }
    }

    fun release() {
        if (started) {
            try { muxer.stop() } catch (_: IllegalStateException) { /* empty */ }
        }
        muxer.release()
    }
}
