package co.crackn.kompressor.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import co.crackn.kompressor.CODEC_TIMEOUT_US
import co.crackn.kompressor.PROGRESS_SETUP
import co.crackn.kompressor.REMUX_BUFFER_SIZE
import co.crackn.kompressor.reportMediaCodecProgress
import co.crackn.kompressor.safeRelease
import co.crackn.kompressor.safeInt
import co.crackn.kompressor.safeLong
import co.crackn.kompressor.safeStopAndRelease
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * Surface-to-surface H.264 video transcoder.
 *
 * Uses a zero-copy pipeline: the video decoder renders frames directly onto the
 * encoder's input [Surface], avoiding CPU-side pixel copies entirely. Audio is
 * remuxed (bitstream copy) in a second pass after video encoding completes.
 */
@Suppress("TooManyFunctions")
internal class VideoTranscoder(
    private val inputPath: String,
    private val outputPath: String,
    private val config: VideoCompressionConfig,
) {
    suspend fun transcode(onProgress: suspend (Float) -> Unit) {
        val extractor = MediaExtractor().apply { setDataSource(inputPath) }
        try {
            val videoIdx = findTrack(extractor, VIDEO_PREFIX)
            check(videoIdx >= 0) { "No video track found in input file" }
            val audioIdx = findTrack(extractor, AUDIO_PREFIX)

            val videoFormat = extractor.getTrackFormat(videoIdx)
            val audioFormat = if (audioIdx >= 0) extractor.getTrackFormat(audioIdx) else null
            val totalDurationUs = videoFormat.safeLong(MediaFormat.KEY_DURATION)
            val (targetW, targetH) = calculateTargetDimensions(videoFormat)
            val rotationDegrees = videoFormat.safeInt(MediaFormat.KEY_ROTATION)

            onProgress(PROGRESS_SETUP)
            currentCoroutineContext().ensureActive()

            transcodeWithResources(
                extractor, videoIdx, audioFormat, targetW, targetH, totalDurationUs, rotationDegrees, onProgress,
            )
        } finally {
            extractor.release()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun transcodeWithResources(
        extractor: MediaExtractor,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        targetW: Int,
        targetH: Int,
        totalDurationUs: Long,
        rotationDegrees: Int,
        onProgress: suspend (Float) -> Unit,
    ) {
        val encoderFormat = buildEncoderFormat(targetW, targetH)
        val encoder = MediaCodec.createEncoderByType(H264_MIME)
        try {
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            try {
                encoder.start()
                transcodeWithCodecs(
                    extractor, encoder, inputSurface, videoIdx,
                    audioFormat, totalDurationUs, rotationDegrees, onProgress,
                )
            } finally {
                inputSurface.release()
            }
        } finally {
            encoder.safeRelease()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun transcodeWithCodecs(
        extractor: MediaExtractor,
        encoder: MediaCodec,
        inputSurface: Surface,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        totalDurationUs: Long,
        rotationDegrees: Int,
        onProgress: suspend (Float) -> Unit,
    ) {
        val decoder = createDecoder(extractor.getTrackFormat(videoIdx), inputSurface)
        try {
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                runPipeline(
                    extractor, decoder, encoder, muxer,
                    videoIdx, audioFormat, totalDurationUs, rotationDegrees, onProgress,
                )
            } finally {
                muxer.safeStopAndRelease()
            }
        } finally {
            decoder.safeRelease()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun runPipeline(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        totalDurationUs: Long,
        rotationDegrees: Int,
        onProgress: suspend (Float) -> Unit,
    ) {
        extractor.selectTrack(videoIdx)
        val loop = VideoTranscodeLoop(extractor, decoder, encoder, muxer, audioFormat, rotationDegrees)
        loop.run(totalDurationUs, onProgress)

        // Second pass: remux audio track from the original file
        if (audioFormat != null && loop.muxerAudioTrack >= 0) {
            remuxAudio(muxer, loop.muxerAudioTrack)
        }
    }

    private suspend fun remuxAudio(muxer: MediaMuxer, muxerAudioTrack: Int) {
        val audioExtractor = MediaExtractor().apply { setDataSource(inputPath) }
        try {
            val audioIdx = findTrack(audioExtractor, AUDIO_PREFIX)
            if (audioIdx < 0) return
            audioExtractor.selectTrack(audioIdx)

            val buffer = ByteBuffer.allocate(REMUX_BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val size = audioExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                muxer.writeSampleData(muxerAudioTrack, buffer, info)
                audioExtractor.advance()
                yield()
            }
        } finally {
            audioExtractor.release()
        }
    }

    private fun calculateTargetDimensions(videoFormat: MediaFormat): Pair<Int, Int> {
        val sourceW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val sourceH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        return ResolutionCalculator.calculate(sourceW, sourceH, config.maxResolution)
    }

    private fun buildEncoderFormat(width: Int, height: Int): MediaFormat =
        MediaFormat.createVideoFormat(H264_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.maxFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameInterval)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }

    private fun createDecoder(inputFormat: MediaFormat, outputSurface: Surface): MediaCodec {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("No video MIME type")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, outputSurface, null, 0)
        decoder.start()
        return decoder
    }
}

/**
 * Single-threaded video transcode loop: feed extractor → decode → render to Surface → encode → mux.
 *
 * All stages run in the same iteration to prevent [MediaCodec] buffer-pool
 * deadlock: the encoder cannot free input slots until its output is consumed.
 * The decoder renders decoded frames directly onto the encoder's input Surface
 * (zero-copy).
 */
@Suppress("TooManyFunctions")
private class VideoTranscodeLoop(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    audioFormat: MediaFormat?,
    private val rotationDegrees: Int = 0,
) {
    private val decoderInfo = MediaCodec.BufferInfo()
    private val encoderInfo = MediaCodec.BufferInfo()

    // Audio track index in the muxer — set before muxer starts
    var muxerAudioTrack: Int = -1
        private set

    private var muxerVideoTrack = -1
    private var pendingAudioFormat: MediaFormat? = audioFormat
    private var muxerStarted = false
    private var extractorDone = false
    private var decoderDone = false
    private var lastProgress = PROGRESS_SETUP

    suspend fun run(totalDurationUs: Long, onProgress: suspend (Float) -> Unit) {
        var idleDrains = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!extractorDone) feedDecoder()
            if (!decoderDone) drainDecoder()
            val done = drainEncoder(totalDurationUs, onProgress)
            if (done) break
            idleDrains = if (extractorDone && decoderDone) idleDrains + 1 else 0
            check(idleDrains < MAX_IDLE_DRAINS) { "Encoder stalled: EOS never received" }
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

    private fun drainDecoder() {
        val status = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)
        if (status < 0) return
        val isEos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        // In Surface output mode, size is unreliable — always render non-EOS frames
        decoder.releaseOutputBuffer(status, /* render = */ !isEos)
        if (isEos) {
            encoder.signalEndOfInputStream()
            decoderDone = true
        }
    }

    @Suppress("ReturnCount")
    private suspend fun drainEncoder(
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ): Boolean {
        val status = encoder.dequeueOutputBuffer(encoderInfo, CODEC_TIMEOUT_US)
        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            startMuxer()
            return false
        }
        if (status < 0) return false

        val buf = encoder.getOutputBuffer(status) ?: error("Encoder output null")
        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoderInfo.size = 0
        if (encoderInfo.size > 0 && muxerStarted) {
            muxer.writeSampleData(muxerVideoTrack, buf, encoderInfo)
        }
        encoder.releaseOutputBuffer(status, false)
        lastProgress = reportMediaCodecProgress(
            encoderInfo.presentationTimeUs, totalDurationUs, lastProgress, onProgress,
        )
        return encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun startMuxer() {
        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
        pendingAudioFormat?.let { fmt ->
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime in MP4_SUPPORTED_AUDIO_MIMES) {
                muxerAudioTrack = muxer.addTrack(fmt)
            }
        }
        if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)
        muxer.start()
        muxerStarted = true
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(mimePrefix)) return i
    }
    return -1
}

private const val H264_MIME = "video/avc"
private const val VIDEO_PREFIX = "video/"
private const val AUDIO_PREFIX = "audio/"
private const val MAX_IDLE_DRAINS = 1_000
private val MP4_SUPPORTED_AUDIO_MIMES = setOf("audio/mp4a-latm", "audio/3gpp/amr-nb", "audio/3gpp/amr-wb")
