package co.crackn.kompressor.testutil

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Produces an MP4 file containing N independent AAC audio tracks, each carrying a different
 * mono sine-tone frequency. Used by [co.crackn.kompressor.MultiTrackAudioSelectionTest] to
 * verify that `AudioCompressionConfig.audioTrackIndex` actually routes the right track to the
 * encoder (the output's dominant frequency tracks the selected input track).
 *
 * Approach: allocate one `MediaCodec` AAC encoder per requested tone, feed each its own PCM
 * stream, and mux every encoder's output into a dedicated track of a single `MediaMuxer`.
 * Encoders are driven in a round-robin loop so all tracks share the same wall-clock progress.
 */
object MultiTrackAudioFixture {

    private const val AAC_MIME = "audio/mp4a-latm"
    private const val SAMPLE_RATE = 44_100
    private const val CHANNELS = 1
    private const val BITRATE = 96_000
    private const val BYTES_PER_SAMPLE = 2
    private const val TIMEOUT_US = 10_000L

    /**
     * Write [durationSec] seconds of audio into [output] with one mono AAC track per entry in
     * [trackFrequencies] (Hz). The returned file's nth audio track (zero-based) carries the
     * nth frequency in the list.
     */
    fun createMultiTrackAudioMp4(
        output: File,
        durationSec: Int,
        trackFrequencies: List<Int>,
    ): File {
        require(durationSec > 0) { "durationSec must be > 0" }
        require(trackFrequencies.isNotEmpty()) { "at least one track required" }

        val pcmStreams = trackFrequencies.map { freq -> generateMonoPcm(durationSec, freq.toDouble()) }
        val encoders = trackFrequencies.map { buildAacEncoder() }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTracks = IntArray(encoders.size) { -1 }
        val formatReported = BooleanArray(encoders.size)
        val inputDone = BooleanArray(encoders.size)
        val outputDone = BooleanArray(encoders.size)
        val srcOffsets = IntArray(encoders.size)
        val ptsUs = LongArray(encoders.size)
        val remainderNum = LongArray(encoders.size)
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        try {
            encoders.forEach { it.start() }
            while (outputDone.any { !it }) {
                // Feed each encoder that still has input.
                for (i in encoders.indices) {
                    if (inputDone[i]) continue
                    feedEncoder(
                        encoder = encoders[i],
                        pcm = pcmStreams[i],
                        srcOffsets = srcOffsets,
                        ptsUs = ptsUs,
                        remainderNum = remainderNum,
                        inputDone = inputDone,
                        trackIndex = i,
                    )
                }
                // Drain each encoder.
                for (i in encoders.indices) {
                    if (outputDone[i]) continue
                    outputDone[i] = drainEncoder(
                        encoder = encoders[i],
                        muxer = muxer,
                        muxerTracks = muxerTracks,
                        trackIndex = i,
                        formatReported = formatReported,
                        info = info,
                        startMuxerWhenReady = {
                            if (!muxerStarted && formatReported.all { it }) {
                                muxer.start()
                                muxerStarted = true
                            }
                            muxerStarted
                        },
                    )
                }
            }
        } finally {
            encoders.forEach {
                runCatching { it.stop() }
                it.release()
            }
            runCatching { if (muxerStarted) muxer.stop() }
            muxer.release()
        }
        return output
    }

    private fun buildAacEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(AAC_MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        return MediaCodec.createEncoderByType(AAC_MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    @Suppress("LongParameterList")
    private fun feedEncoder(
        encoder: MediaCodec,
        pcm: ByteArray,
        srcOffsets: IntArray,
        ptsUs: LongArray,
        remainderNum: LongArray,
        inputDone: BooleanArray,
        trackIndex: Int,
    ) {
        val idx = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (idx < 0) return
        val buf = encoder.getInputBuffer(idx) ?: error("encoder input null")
        val srcOffset = srcOffsets[trackIndex]
        val remaining = pcm.size - srcOffset
        val bytesPerFrame = CHANNELS * BYTES_PER_SAMPLE
        if (remaining < bytesPerFrame) {
            encoder.queueInputBuffer(
                idx, 0, 0, ptsUs[trackIndex], MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputDone[trackIndex] = true
            return
        }
        var toCopy = minOf(buf.capacity(), remaining)
        toCopy -= toCopy % bytesPerFrame
        buf.put(pcm, srcOffset, toCopy)
        encoder.queueInputBuffer(idx, 0, toCopy, ptsUs[trackIndex], 0)
        srcOffsets[trackIndex] = srcOffset + toCopy
        val frames = (toCopy / bytesPerFrame).toLong()
        val deltaNum = frames * 1_000_000L + remainderNum[trackIndex]
        ptsUs[trackIndex] += deltaNum / SAMPLE_RATE
        remainderNum[trackIndex] = deltaNum % SAMPLE_RATE
    }

    @Suppress("LongParameterList")
    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        muxerTracks: IntArray,
        trackIndex: Int,
        formatReported: BooleanArray,
        info: MediaCodec.BufferInfo,
        startMuxerWhenReady: () -> Boolean,
    ): Boolean {
        val status = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                muxerTracks[trackIndex] = muxer.addTrack(encoder.outputFormat)
                formatReported[trackIndex] = true
            }
            status >= 0 -> {
                val out = encoder.getOutputBuffer(status) ?: error("encoder output null")
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                val started = startMuxerWhenReady()
                if (info.size > 0 && started) {
                    muxer.writeSampleData(muxerTracks[trackIndex], out, info)
                }
                encoder.releaseOutputBuffer(status, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }
        return false
    }

    private fun generateMonoPcm(durationSec: Int, freq: Double): ByteArray {
        val totalSamples = SAMPLE_RATE * durationSec
        val out = ByteArray(totalSamples * BYTES_PER_SAMPLE)
        var offset = 0
        for (i in 0 until totalSamples) {
            val sample = (Short.MAX_VALUE * sin(2.0 * PI * freq * i / SAMPLE_RATE)).toInt().toShort()
            out[offset++] = (sample.toInt() and 0xFF).toByte()
            out[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
