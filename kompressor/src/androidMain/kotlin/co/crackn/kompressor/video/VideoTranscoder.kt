package co.crackn.kompressor.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import co.crackn.kompressor.PROGRESS_SETUP
import co.crackn.kompressor.REMUX_BUFFER_SIZE
import co.crackn.kompressor.reportMediaCodecProgress
import co.crackn.kompressor.safeRelease
import co.crackn.kompressor.safeLong
import co.crackn.kompressor.safeStopAndRelease
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * H.264 video transcoder with hardware-first, software-fallback strategy.
 *
 * **Hardware path** (default): Surface-to-surface zero-copy pipeline — the video
 * decoder renders frames directly onto the encoder's input [Surface], avoiding
 * CPU-side pixel copies entirely.
 *
 * **Software path** (fallback): ByteBuffer pipeline — decoded YUV frames are copied
 * from the decoder's output buffer to the encoder's input buffer. Slower (~3-5x)
 * but works universally without hardware resource constraints.
 *
 * The encoder is selected via [CodecSelector] which queries [android.media.MediaCodecList]
 * to find the best codec for the target resolution, auto-caps dimensions to hardware
 * limits, and provides software fallback when hardware fails.
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
            logTrackInfo(extractor, videoIdx, audioIdx)
            validateDeviceCanDecode(videoFormat)

            val info = extractTranscodeInfo(videoFormat)
            val (effectiveEncoder, cappedW, cappedH) = selectEncoder(
                info.sourceW, info.sourceH, info.targetW, info.targetH,
            )
            Log.i(TAG, "Encoder: ${effectiveEncoder.codecName} " +
                "(surface=${effectiveEncoder.useSurface}) " +
                "target=${cappedW}x$cappedH source=${info.sourceW}x${info.sourceH}")

            onProgress(PROGRESS_SETUP)
            currentCoroutineContext().ensureActive()

            transcodeWithFallback(
                extractor, videoIdx, audioFormat, cappedW, cappedH,
                info.totalDurationUs, effectiveEncoder, onProgress,
            )
        } finally {
            extractor.release()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun transcodeWithFallback(
        extractor: MediaExtractor,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        width: Int,
        height: Int,
        totalDurationUs: Long,
        primaryEncoder: CodecSelector.EncoderChoice,
        onProgress: suspend (Float) -> Unit,
    ) {
        Log.i(TAG, "Attempt 1: ${primaryEncoder.codecName} (surface=${primaryEncoder.useSurface})")
        val primaryError = tryTranscode(
            extractor, videoIdx, audioFormat, width, height,
            totalDurationUs, primaryEncoder, onProgress,
        )
        if (primaryError == null) return // Success on first try
        Log.w(TAG, "Attempt 1 failed: ${primaryError.message}", primaryError)

        // Retry with software encoder if primary was hardware. If primary was
        // already software, no retry makes sense — the error is not transient.
        val primaryWasHardware = primaryEncoder.useSurface
        if (primaryWasHardware) {
            val swEncoder = CodecSelector.findSoftwareEncoder()
            if (swEncoder != null) {
                Log.i(TAG, "Attempt 2: ${swEncoder.codecName} (software fallback)")
                resetExtractor(extractor, videoIdx)
                val swError = tryTranscode(
                    extractor, videoIdx, audioFormat, width, height,
                    totalDurationUs, swEncoder, onProgress,
                )
                if (swError == null) return // Software succeeded
                Log.w(TAG, "Attempt 2 failed: ${swError.message}", swError)
            }
        }
        Log.e(TAG, "All transcode attempts failed", primaryError)
        throw primaryError
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun tryTranscode(
        extractor: MediaExtractor,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        width: Int,
        height: Int,
        totalDurationUs: Long,
        encoder: CodecSelector.EncoderChoice,
        onProgress: suspend (Float) -> Unit,
    ): Exception? {
        return try {
            if (encoder.useSurface) {
                transcodeWithSurface(
                    extractor, videoIdx, audioFormat, width, height,
                    totalDurationUs, encoder.codecName, onProgress,
                )
            } else {
                transcodeWithByteBuffers(
                    extractor, videoIdx, audioFormat, width, height,
                    totalDurationUs, encoder.codecName, onProgress,
                )
            }
            null
        } catch (e: Exception) {
            e
        }
    }

    // ── Surface-to-Surface path (hardware, zero-copy) ───────────────

    @Suppress("LongParameterList")
    private suspend fun transcodeWithSurface(
        extractor: MediaExtractor,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        width: Int,
        height: Int,
        totalDurationUs: Long,
        codecName: String,
        onProgress: suspend (Float) -> Unit,
    ) {
        val format = buildEncoderFormat(width, height, useSurface = true)
        val encoder = MediaCodec.createByCodecName(codecName)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            try {
                encoder.start()
                stabilizeEncoder(encoder)
                runSurfaceWithDecoder(
                    extractor, encoder, inputSurface,
                    videoIdx, audioFormat, totalDurationUs, onProgress,
                )
            } finally { inputSurface.release() }
        } finally { encoder.safeRelease() }
    }

    @Suppress("LongParameterList")
    private suspend fun runSurfaceWithDecoder(
        extractor: MediaExtractor,
        encoder: MediaCodec,
        inputSurface: Surface,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val decoder = createDecoder(extractor.getTrackFormat(videoIdx), outputSurface = inputSurface)
        try {
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                runSurfacePipeline(
                    extractor, decoder, encoder, muxer,
                    videoIdx, audioFormat, totalDurationUs, onProgress,
                )
            } finally { muxer.safeStopAndRelease() }
        } finally { decoder.safeRelease() }
    }

    // ── ByteBuffer path (software fallback) ─────────────────────────

    @Suppress("LongParameterList")
    private suspend fun transcodeWithByteBuffers(
        extractor: MediaExtractor,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        width: Int,
        height: Int,
        totalDurationUs: Long,
        codecName: String,
        onProgress: suspend (Float) -> Unit,
    ) {
        val format = buildEncoderFormat(width, height, useSurface = false)
        val encoder = MediaCodec.createByCodecName(codecName)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            // Software encoder path: also use software decoder to avoid
            // hardware resource conflicts that caused the hardware path to fail
            val decoder = createDecoder(
                extractor.getTrackFormat(videoIdx),
                outputSurface = null,
                forceSoftware = true,
            )
            try {
                val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    runByteBufferPipeline(
                        extractor, decoder, encoder, muxer,
                        videoIdx, audioFormat, totalDurationUs, onProgress,
                    )
                } finally { muxer.safeStopAndRelease() }
            } finally { decoder.safeRelease() }
        } finally { encoder.safeRelease() }
    }

    // ── Pipeline runners ────────────────────────────────────────────

    @Suppress("LongParameterList")
    private suspend fun runSurfacePipeline(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        extractor.selectTrack(videoIdx)
        val loop = SurfaceTranscodeLoop(extractor, decoder, encoder, muxer, audioFormat)
        loop.run(totalDurationUs, onProgress)
        remuxAudioIfPresent(audioFormat, muxer, loop.muxerAudioTrack)
    }

    @Suppress("LongParameterList")
    private suspend fun runByteBufferPipeline(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        videoIdx: Int,
        audioFormat: MediaFormat?,
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        extractor.selectTrack(videoIdx)
        val loop = ByteBufferTranscodeLoop(extractor, decoder, encoder, muxer, audioFormat)
        loop.run(totalDurationUs, onProgress)
        remuxAudioIfPresent(audioFormat, muxer, loop.muxerAudioTrack)
    }

    private suspend fun remuxAudioIfPresent(
        audioFormat: MediaFormat?,
        muxer: MediaMuxer,
        muxerAudioTrack: Int,
    ) {
        if (audioFormat != null && muxerAudioTrack >= 0) {
            remuxAudio(muxer, muxerAudioTrack)
        }
    }

    // ── Shared helpers ──────────────────────────────────────────────

    private suspend fun stabilizeEncoder(encoder: MediaCodec) {
        delay(STABILIZE_DELAY_MS)
        val info = MediaCodec.BufferInfo()
        val status = encoder.dequeueOutputBuffer(info, STABILIZE_PROBE_US)
        if (status >= 0) encoder.releaseOutputBuffer(status, false)
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

    private fun resetExtractor(extractor: MediaExtractor, videoIdx: Int) {
        extractor.unselectTrack(videoIdx)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    }

    /**
     * Selects the best encoder and caps output dimensions.
     *
     * If the source resolution exceeds the hardware encoder's max, both hardware
     * codecs (decoder + encoder) running simultaneously will exhaust hardware
     * resources and crash with SIGSEGV. In that case, skip straight to software.
     */
    private fun selectEncoder(
        sourceW: Int,
        sourceH: Int,
        targetW: Int,
        targetH: Int,
    ): Triple<CodecSelector.EncoderChoice, Int, Int> {
        val encoder = CodecSelector.findEncoder(targetW, targetH)
            ?: error("No H.264 encoder available on this device")
        val inputExceedsHardware = sourceW > encoder.maxWidth || sourceH > encoder.maxHeight
        val effective = if (inputExceedsHardware) {
            CodecSelector.findSoftwareEncoder() ?: encoder
        } else {
            encoder
        }
        val (cappedW, cappedH) = CodecSelector.capToEncoderLimits(targetW, targetH, effective)
        return Triple(effective, cappedW, cappedH)
    }

    private fun buildEncoderFormat(width: Int, height: Int, useSurface: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(H264_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.maxFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameInterval)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (useSurface) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
        }

    /**
     * Creates and starts a video decoder.
     *
     * When [forceSoftware] is true, uses a software decoder to avoid hardware
     * resource conflicts (e.g., when the hardware encoder already failed and
     * we're retrying with a software encoder — the hardware decoder will
     * likely fail too for the same resource reason).
     */
    private fun createDecoder(
        inputFormat: MediaFormat,
        outputSurface: Surface?,
        forceSoftware: Boolean = false,
    ): MediaCodec {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("No video MIME type")
        val decoder = if (forceSoftware) {
            val swName = CodecSelector.findSoftwareDecoder(mime)
                ?: error("No software decoder available for $mime")
            MediaCodec.createByCodecName(swName)
        } else {
            MediaCodec.createDecoderByType(mime)
        }
        decoder.configure(inputFormat, outputSurface, null, 0)
        decoder.start()
        return decoder
    }

    private data class TranscodeInfo(
        val sourceW: Int,
        val sourceH: Int,
        val targetW: Int,
        val targetH: Int,
        val totalDurationUs: Long,
    )

    private fun extractTranscodeInfo(videoFormat: MediaFormat): TranscodeInfo {
        val sourceW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val sourceH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val (targetW, targetH) = ResolutionCalculator.calculate(
            sourceW, sourceH, config.maxResolution,
        )
        return TranscodeInfo(
            sourceW, sourceH, targetW, targetH,
            videoFormat.safeLong(MediaFormat.KEY_DURATION),
        )
    }

    /**
     * Validates that the device can decode the source video.
     *
     * Samsung A53 and similar mid-range devices don't support HEVC Main 10
     * (10-bit HDR) above 1080p. Failing early with a clear message is better
     * than trying and crashing with cryptic codec errors.
     */
    private fun validateDeviceCanDecode(videoFormat: MediaFormat) {
        val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val bitsPerSample = try {
            videoFormat.getInteger(KEY_BITS_PER_SAMPLE)
        } catch (_: Exception) {
            BITS_PER_SAMPLE_8
        }
        // 10-bit HDR above 1080p is rarely supported on mid-range devices.
        val is10Bit = bitsPerSample > BITS_PER_SAMPLE_8
        val isAbove1080p = minOf(width, height) > HD_1080_SHORT_EDGE
        check(!(is10Bit && isAbove1080p)) {
            "Input video uses ${bitsPerSample}-bit $mime at ${width}x$height, " +
                "which is not supported by this device. " +
                "Please use an 8-bit video or a resolution at or below 1080p."
        }
        val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findDecoderForFormat(videoFormat)
        check(decoderName != null) {
            "No decoder available for $mime at ${width}x$height on this device."
        }
    }

    private fun logTrackInfo(extractor: MediaExtractor, videoIdx: Int, audioIdx: Int) {
        Log.i(TAG, "Track count: ${extractor.trackCount}")
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            Log.i(TAG, "Track $i: $format")
        }
        Log.i(TAG, "Video track: $videoIdx, Audio track: $audioIdx")
    }

    private companion object {
        const val TAG = "Kompressor"
        // MediaFormat.KEY_BITS_PER_SAMPLE is API 28+; this literal works on API 24+.
        const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
        const val BITS_PER_SAMPLE_8 = 8
        const val HD_1080_SHORT_EDGE = 1080
    }
}

// ── Surface transcode loop (hardware, zero-copy) ────────────────────

/**
 * Transcode loop for Surface-to-Surface pipeline.
 *
 * The decoder renders decoded frames directly onto the encoder's input Surface.
 * All codec operations are wrapped in try/catch for error recovery.
 */
@Suppress("TooManyFunctions")
private abstract class BaseTranscodeLoop(
    protected val extractor: MediaExtractor,
    protected val decoder: MediaCodec,
    protected val encoder: MediaCodec,
    protected val muxer: MediaMuxer,
    audioFormat: MediaFormat?,
) {
    protected val decoderInfo = MediaCodec.BufferInfo()
    protected val encoderInfo = MediaCodec.BufferInfo()

    var muxerAudioTrack: Int = -1
        private set

    private var muxerVideoTrack = -1
    private val pendingAudioFormat: MediaFormat? = audioFormat
    protected var muxerStarted = false
        private set
    protected var extractorDone = false
    protected var decoderDone = false
    private var lastProgress = PROGRESS_SETUP

    /**
     * Drain one decoded frame — subclass-specific (render to Surface or copy to ByteBuffer).
     * Must set [decoderDone] when the decoder emits EOS.
     */
    protected abstract suspend fun drainDecoder()

    @Suppress("TooGenericExceptionCaught")
    suspend fun run(totalDurationUs: Long, onProgress: suspend (Float) -> Unit) {
        var idleDrains = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                if (!extractorDone) feedDecoder()
                if (!decoderDone) drainDecoder()
                val done = drainEncoder(totalDurationUs, onProgress)
                if (done) break
            } catch (e: MediaCodec.CodecException) {
                throw IllegalStateException("Video codec failed: ${e.diagnosticInfo}", e)
            } catch (e: IllegalStateException) {
                throw IllegalStateException("Video codec entered invalid state", e)
            }
            idleDrains = if (extractorDone && decoderDone) idleDrains + 1 else 0
            check(idleDrains < MAX_IDLE_DRAINS) { "Encoder stalled: EOS never received" }
            yield()
        }
    }

    private fun feedDecoder() {
        val idx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
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

    @Suppress("ReturnCount")
    private suspend fun drainEncoder(
        totalDurationUs: Long,
        onProgress: suspend (Float) -> Unit,
    ): Boolean {
        val status = encoder.dequeueOutputBuffer(encoderInfo, DEQUEUE_TIMEOUT_US)
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
        pendingAudioFormat?.let { muxerAudioTrack = muxer.addTrack(it) }
        muxer.start()
        muxerStarted = true
    }

    /** Block until an encoder input buffer is available, yielding between polls. */
    protected suspend fun awaitEncoderInput(): Int {
        while (true) {
            currentCoroutineContext().ensureActive()
            val idx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx >= 0) return idx
            yield()
        }
    }
}

/**
 * Transcode loop for Surface-to-Surface pipeline (zero-copy).
 * The decoder renders decoded frames directly onto the encoder's input Surface.
 */
private class SurfaceTranscodeLoop(
    extractor: MediaExtractor,
    decoder: MediaCodec,
    encoder: MediaCodec,
    muxer: MediaMuxer,
    audioFormat: MediaFormat?,
) : BaseTranscodeLoop(extractor, decoder, encoder, muxer, audioFormat) {

    override suspend fun drainDecoder() {
        val status = decoder.dequeueOutputBuffer(decoderInfo, DEQUEUE_TIMEOUT_US)
        if (status < 0) return
        val isEos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        decoder.releaseOutputBuffer(status, /* render = */ !isEos)
        if (isEos) {
            encoder.signalEndOfInputStream()
            decoderDone = true
        }
    }
}

/**
 * Transcode loop for ByteBuffer pipeline (software codecs).
 *
 * Decoded YUV frames are copied from the decoder's output buffer to the
 * encoder's input buffer. Awaits encoder input slots instead of dropping
 * frames, so the output PTS sequence stays intact.
 */
private class ByteBufferTranscodeLoop(
    extractor: MediaExtractor,
    decoder: MediaCodec,
    encoder: MediaCodec,
    muxer: MediaMuxer,
    audioFormat: MediaFormat?,
) : BaseTranscodeLoop(extractor, decoder, encoder, muxer, audioFormat) {

    override suspend fun drainDecoder() {
        val decStatus = decoder.dequeueOutputBuffer(decoderInfo, DEQUEUE_TIMEOUT_US)
        if (decStatus < 0) return
        val isEos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

        if (decoderInfo.size > 0) {
            val decodedBuf = decoder.getOutputBuffer(decStatus) ?: error("Decoder output null")
            copyFrameToEncoder(decodedBuf, decoderInfo)
        }
        decoder.releaseOutputBuffer(decStatus, false)

        if (isEos) {
            signalEncoderEos()
            decoderDone = true
        }
    }

    private suspend fun copyFrameToEncoder(decodedBuf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val encIdx = awaitEncoderInput()
        val encBuf = encoder.getInputBuffer(encIdx) ?: error("Encoder input null")
        decodedBuf.position(info.offset)
        decodedBuf.limit(info.offset + info.size)
        encBuf.clear()
        val copySize = minOf(decodedBuf.remaining(), encBuf.capacity())
        val slice = decodedBuf.slice()
        slice.limit(copySize)
        encBuf.put(slice)
        encoder.queueInputBuffer(encIdx, 0, copySize, info.presentationTimeUs, 0)
    }

    private suspend fun signalEncoderEos() {
        val idx = awaitEncoderInput()
        encoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val STABILIZE_DELAY_MS = 100L
private const val STABILIZE_PROBE_US = 10_000L
