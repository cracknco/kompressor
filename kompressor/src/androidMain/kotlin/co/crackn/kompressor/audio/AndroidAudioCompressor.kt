package co.crackn.kompressor.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.CODEC_TIMEOUT_US
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.PROGRESS_SETUP
import co.crackn.kompressor.PROGRESS_TRANSCODE_RANGE
import co.crackn.kompressor.REMUX_BUFFER_SIZE
import co.crackn.kompressor.reportMediaCodecProgress
import co.crackn.kompressor.safeInt
import co.crackn.kompressor.safeLong
import co.crackn.kompressor.safeRelease
import co.crackn.kompressor.safeStopAndRelease
import co.crackn.kompressor.suspendRunCatching
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

        val extractor = MediaExtractor().apply { setDataSource(inputPath) }
        try {
            val (trackIndex, trackFormat) = findAudioTrack(extractor)
            extractor.selectTrack(trackIndex)
            val totalDurationUs = trackFormat.safeLong(MediaFormat.KEY_DURATION)
            onProgress(PROGRESS_SETUP)
            currentCoroutineContext().ensureActive()

            if (canRemux(trackFormat, config)) {
                remux(extractor, outputPath, trackIndex, totalDurationUs, onProgress)
            } else {
                val inputMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: error("No MIME type")
                transcode(extractor, outputPath, inputMime, trackFormat, config, totalDurationUs, onProgress)
            }
        } finally {
            extractor.release()
        }

        onProgress(1f)
        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

// ── Remux fast-path: AAC → AAC bitstream copy ───────────────────────

private fun canRemux(trackFormat: MediaFormat, config: AudioCompressionConfig): Boolean {
    val channelCount = config.channels.count
    val mime = trackFormat.getString(MediaFormat.KEY_MIME)
    val inputRate = trackFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE)
    val inputChannels = trackFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT)
    val inputBitrate = trackFormat.safeInt(MediaFormat.KEY_BIT_RATE)
    val threshold = (config.bitrate * BITRATE_TOLERANCE).toInt()
    return mime == AAC_MIME &&
        (inputRate == 0 || inputRate == config.sampleRate) &&
        (inputChannels == 0 || inputChannels == channelCount) &&
        (inputBitrate <= 0 || inputBitrate >= threshold)
}

private suspend fun remux(
    extractor: MediaExtractor,
    outputPath: String,
    trackIndex: Int,
    totalDurationUs: Long,
    onProgress: suspend (Float) -> Unit,
) {
    val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val muxerTrack = muxer.addTrack(extractor.getTrackFormat(trackIndex))
    muxer.start()
    try {
        val buffer = ByteBuffer.allocate(REMUX_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        var lastProgress = PROGRESS_SETUP
        while (true) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(muxerTrack, buffer, info)
            lastProgress = reportMediaCodecProgress(extractor.sampleTime, totalDurationUs, lastProgress, onProgress)
            extractor.advance()
            yield()
        }
    } finally {
        muxer.safeStopAndRelease()
    }
}

// ── Full transcode: decode → encode → mux ───────────────────────────

private fun createProcessor(
    inputFormat: MediaFormat,
    config: AudioCompressionConfig,
    outputChannels: Int,
): PcmProcessor? {
    val inputRate = inputFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE).let {
        if (it > 0) it else config.sampleRate
    }
    val inputChannels = inputFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT).let {
        if (it > 0) it else outputChannels
    }
    if (inputRate == config.sampleRate && inputChannels == outputChannels) return null
    return PcmProcessor(inputRate, config.sampleRate, inputChannels, outputChannels)
}

@Suppress("LongParameterList")
private suspend fun transcode(
    extractor: MediaExtractor,
    outputPath: String,
    inputMime: String,
    inputFormat: MediaFormat,
    config: AudioCompressionConfig,
    totalDurationUs: Long,
    onProgress: suspend (Float) -> Unit,
) {
    val outputChannels = config.channels.count
    val processor = createProcessor(inputFormat, config, outputChannels)
    val outputFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, outputChannels,
    ).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    }
    val decoder = MediaCodec.createDecoderByType(inputMime)
    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        decoder.configure(inputFormat, null, null, 0)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        decoder.start()
        encoder.start()
        TranscodeLoop(
            extractor, decoder, encoder, muxer, processor, config.sampleRate, outputChannels,
        ).run(totalDurationUs, onProgress)
    } finally {
        decoder.safeRelease()
        encoder.safeRelease()
        muxer.safeStopAndRelease()
    }
}

/**
 * Single-threaded sequential loop: feed → decode → (resample) → encode → mux.
 *
 * All stages run in the same iteration — this naturally prevents the MediaCodec
 * buffer-pool deadlock that occurs when input and output are not interleaved
 * (the encoder cannot free input slots until its output is consumed).
 *
 * A [PcmProcessor] handles sample-rate and channel conversion when the input format
 * differs from the target config.  A [PcmRingBuffer] accumulates decoded PCM so that
 * encoder input buffers are always filled completely, preventing silent data loss when
 * decoder output exceeds encoder buffer capacity.
 */
@Suppress("TooManyFunctions", "LongParameterList")
private class TranscodeLoop(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val processor: PcmProcessor?,
    private val outputSampleRate: Int,
    private val outputChannels: Int,
) {
    private val decoderInfo = MediaCodec.BufferInfo()
    private val encoderInfo = MediaCodec.BufferInfo()
    private val pcmBuffer = PcmRingBuffer(outputChannels * BYTES_PER_SAMPLE)
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var extractorDone = false
    private var decoderDone = false
    private var outputSamplesWritten = 0L
    private var cachedEncoderCapacity = DEFAULT_ENCODER_BUF_SIZE
    private var lastProgress = PROGRESS_SETUP

    suspend fun run(totalDurationUs: Long, onProgress: suspend (Float) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val bufferFull = pcmBuffer.size >= PCM_BUFFER_HIGH_WATER
            if (!extractorDone && !bufferFull) feedDecoder()
            if (!decoderDone && !bufferFull) drainDecoder()
            if (bufferFull) feedEncoderFromBuffer()
            val encoderDone = drainEncoder(totalDurationUs, onProgress)
            if (encoderDone) break
            yield()
        }
    }

    private fun feedDecoder() {
        val idx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (idx < 0) return
        val buf = decoder.getInputBuffer(idx) ?: return
        val size = extractor.readSampleData(buf, 0)
        if (size < 0) {
            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            extractorDone = true
        } else {
            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private suspend fun drainDecoder() {
        val status = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)
        if (status < 0) return
        val isEos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

        if (decoderInfo.size > 0) {
            val decoded = decoder.getOutputBuffer(status) ?: error("Decoder output null")
            decoded.position(decoderInfo.offset)
            decoded.limit(decoderInfo.offset + decoderInfo.size)

            val pcmData = processor?.process(decoded) ?: decoded
            pcmBuffer.write(pcmData)
            feedEncoderFromBuffer()
        }

        decoder.releaseOutputBuffer(status, false)

        if (isEos) {
            flushRemainingPcm()
            val eosIdx = awaitEncoderInput()
            encoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            decoderDone = true
        }
    }

    private suspend fun feedEncoderFromBuffer() {
        while (pcmBuffer.hasChunk(cachedEncoderCapacity)) {
            if (!feedOneEncoderChunk()) return
        }
    }

    private fun feedOneEncoderChunk(): Boolean {
        var handled = false
        val encIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (encIdx >= 0) {
            val encBuf = encoder.getInputBuffer(encIdx) ?: error("Encoder input buffer null")
            cachedEncoderCapacity = encBuf.capacity()
            if (pcmBuffer.hasChunk(encBuf.capacity())) {
                val bytes = pcmBuffer.readChunk(encBuf)
                encoder.queueInputBuffer(encIdx, 0, bytes, computePtsUs(), 0)
                advanceSampleCount(bytes)
                handled = true
            }
        }
        return handled
    }

    private suspend fun flushRemainingPcm() {
        while (pcmBuffer.hasRemaining()) {
            val encIdx = awaitEncoderInput()
            val encBuf = encoder.getInputBuffer(encIdx) ?: error("Encoder input null")
            val bytes = pcmBuffer.flush(encBuf)
            encoder.queueInputBuffer(encIdx, 0, bytes, computePtsUs(), 0)
            advanceSampleCount(bytes)
        }
    }

    private fun computePtsUs(): Long =
        outputSamplesWritten * MICROS_PER_SECOND / outputSampleRate

    private fun advanceSampleCount(bytes: Int) {
        val bytesPerFrame = outputChannels * BYTES_PER_SAMPLE
        outputSamplesWritten += bytes / bytesPerFrame
    }


    @Suppress("ReturnCount")
    private suspend fun drainEncoder(
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ): Boolean {
        val status = encoder.dequeueOutputBuffer(encoderInfo, CODEC_TIMEOUT_US)
        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
            muxer.start()
            muxerStarted = true
            return false
        }
        if (status < 0) return false

        val buf = encoder.getOutputBuffer(status) ?: error("Encoder output null")
        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoderInfo.size = 0
        if (encoderInfo.size > 0 && muxerStarted) {
            muxer.writeSampleData(muxerTrackIndex, buf, encoderInfo)
        }
        encoder.releaseOutputBuffer(status, false)
        lastProgress = reportMediaCodecProgress(
            encoderInfo.presentationTimeUs, totalDurationUs, lastProgress, onProgress,
        )
        return encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private suspend fun awaitEncoderInput(): Int {
        while (true) {
            currentCoroutineContext().ensureActive()
            val index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) return index
            // Drain encoder output while waiting to prevent buffer-pool deadlock
            drainEncoder(0, NO_PROGRESS)
            yield()
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

private fun findAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat> {
    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) return i to format
    }
    throw IllegalArgumentException("No audio track found in input file")
}

private val NO_PROGRESS: suspend (Float) -> Unit = {}

private const val BITRATE_TOLERANCE = 0.8f
private const val AAC_MIME = "audio/mp4a-latm"
private const val BYTES_PER_SAMPLE = 2
private const val MICROS_PER_SECOND = 1_000_000L
private const val PCM_BUFFER_HIGH_WATER = PcmRingBuffer.DEFAULT_MAX_CAPACITY / 4
private const val DEFAULT_ENCODER_BUF_SIZE = 8_192
