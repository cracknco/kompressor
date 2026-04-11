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
        val yuvSize = width * height * YUV_BYTES_PER_PIXEL / YUV_DIVISOR
        val yuvData = createSolidYuv(width, height)
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
                        inputBuf.clear()
                        inputBuf.put(yuvData, 0, minOf(yuvData.size, inputBuf.capacity()))
                        val pts = framesSubmitted.toLong() * US_PER_SEC / fps
                        encoder.queueInputBuffer(inputIdx, 0, yuvSize, pts, 0)
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

    private fun createSolidYuv(width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / UV_PLANE_DIVISOR
        val data = ByteArray(ySize + uvSize * 2)
        // Y plane: mid-grey (128)
        data.fill(Y_VALUE.toByte(), 0, ySize)
        // U plane: neutral (128)
        data.fill(UV_VALUE.toByte(), ySize, ySize + uvSize)
        // V plane: neutral (128)
        data.fill(UV_VALUE.toByte(), ySize + uvSize, data.size)
        return data
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
    private const val Y_VALUE = 128
    private const val UV_VALUE = 128
}
