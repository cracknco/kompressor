package co.crackn.kompressor.testutil

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Generates minimal valid MP4 video files for testing.
 *
 * Encodes solid-colour YUV420 frames into H.264 using hardware [MediaCodec],
 * muxed into an MP4 container via [MediaMuxer].
 */
object Mp4Generator {

    /**
     * Generate a valid MP4 file with [frameCount] frames of [width] x [height].
     *
     * @return the generated file.
     */
    fun generateMp4(
        output: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        frameCount: Int = DEFAULT_FRAME_COUNT,
        fps: Int = DEFAULT_FPS,
    ): File {
        val format = MediaFormat.createVideoFormat(H264_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
        }
        val encoder = MediaCodec.createEncoderByType(H264_MIME)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            encodeFrames(encoder, muxer, width, height, frameCount, fps)
        } finally {
            try { encoder.stop() } catch (_: IllegalStateException) { }
            encoder.release()
            try { muxer.stop() } catch (_: IllegalStateException) { }
            muxer.release()
        }
        return output
    }

    @Suppress("LongParameterList")
    private fun encodeFrames(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        width: Int,
        height: Int,
        frameCount: Int,
        fps: Int,
    ) {
        val yuvData = ByteArray(width * height * YUV_BYTES_PER_PIXEL / YUV_DIVISOR)
        val info = MediaCodec.BufferInfo()
        var muxerTrack = -1
        var muxerStarted = false
        var framesSubmitted = 0

        while (true) {
            // Feed input
            if (framesSubmitted <= frameCount) {
                val inputIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIdx >= 0) {
                    val inputBuf = encoder.getInputBuffer(inputIdx) ?: error("No input buffer")
                    if (framesSubmitted < frameCount) {
                        fillVaryingYuv(yuvData, width, height, framesSubmitted)
                        inputBuf.clear()
                        val written = minOf(yuvData.size, inputBuf.capacity())
                        inputBuf.put(yuvData, 0, written)
                        val pts = framesSubmitted.toLong() * US_PER_SEC / fps
                        // Declare the actual bytes written, not the logical yuvSize.
                        // If the codec's buffer is smaller, claiming more would send
                        // garbage past the written region to the encoder.
                        encoder.queueInputBuffer(inputIdx, 0, written, pts, 0)
                    } else {
                        encoder.queueInputBuffer(
                            inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                    framesSubmitted++
                }
            }

            // Drain output
            val outputIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerTrack = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (outputIdx >= 0) {
                val buf = encoder.getOutputBuffer(outputIdx) ?: error("No output buffer")
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && muxerStarted) {
                    muxer.writeSampleData(muxerTrack, buf, info)
                }
                encoder.releaseOutputBuffer(outputIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    /**
     * Fill [dst] with a per-frame-varying YUV420 pattern: a diagonal luma gradient that shifts
     * by [frameIndex] every frame, plus lightly-varied chroma. This gives H.264's rate
     * controller enough spatial + temporal entropy that output size actually tracks the target
     * bitrate (a solid-grey fixture compresses to near-zero at any target — see the earlier
     * bug that surfaced with `compressVideo_bitrateAffectsSize` producing near-identical
     * outputs for low vs high bitrate on the original fixture).
     *
     * Intentionally no per-pixel PRNG noise: H.264 cannot efficiently compress random
     * high-frequency data and the rate controller overshoots its target dramatically (a
     * 1.2 Mbps target produces ~7 Mbps output with dense noise). Structure-only keeps output
     * size tightly correlated with target bitrate, which is what every size/bitrate assertion
     * actually wants. Randomness is the right tool for PNG vs JPEG (where DEFLATE can't find
     * patterns), not for inter-frame-predicted video.
     */
    private fun fillVaryingYuv(dst: ByteArray, width: Int, height: Int, frameIndex: Int) {
        val ySize = width * height
        val uvSize = ySize / UV_PLANE_DIVISOR
        var yIdx = 0
        for (y in 0 until height) {
            val rowBase = y + frameIndex * LUMA_FRAME_PHASE
            for (x in 0 until width) {
                dst[yIdx++] = ((x + rowBase) and BYTE_MASK).toByte()
            }
        }
        for (i in 0 until uvSize) {
            dst[ySize + i] = ((i + frameIndex * CHROMA_U_FRAME_PHASE) and BYTE_MASK).toByte()
        }
        for (i in 0 until uvSize) {
            dst[ySize + uvSize + i] = ((i + frameIndex * CHROMA_V_FRAME_PHASE) and BYTE_MASK).toByte()
        }
    }

    private const val H264_MIME = "video/avc"
    private const val DEFAULT_WIDTH = 320
    private const val DEFAULT_HEIGHT = 240
    private const val DEFAULT_FRAME_COUNT = 30
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_BITRATE = 500_000
    private const val TIMEOUT_US = 10_000L
    private const val US_PER_SEC = 1_000_000L
    private const val YUV_BYTES_PER_PIXEL = 3
    private const val YUV_DIVISOR = 2
    private const val UV_PLANE_DIVISOR = 4
    private const val BYTE_MASK = 0xFF
    private const val LUMA_FRAME_PHASE = 7
    private const val CHROMA_U_FRAME_PHASE = 3
    private const val CHROMA_V_FRAME_PHASE = 5
}
