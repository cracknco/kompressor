/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.random.Random

/**
 * Generates a large 1080p H.264 MP4 on the device, on the fly.
 *
 * Android sibling of `LargeMp4Fixture.swift` (iOS). Target: ≥ 100 MB so the
 * [androidx.media3.transformer.Transformer] streaming pipeline is exercised on an input that
 * cannot fit comfortably in RAM. High-entropy YUV420 frames combined with a 30 Mbps bitrate
 * ceiling and a 1-second keyframe interval defeat H.264 inter-prediction well enough to produce
 * ~200 MB for a 60 s clip.
 *
 * The file is NEVER committed — it is produced in a caller-supplied temp directory and deleted
 * by the test's `tearDown`.
 *
 * This is intentionally a separate utility from [Mp4Generator]: the generator's `fillVaryingYuv`
 * produces *structured* content precisely to keep bitrate predictable (see its KDoc), whereas
 * this fixture needs the opposite — maximum entropy so the encoder overshoots and the file grows
 * to the streaming-stress target.
 */
object LargeMp4Fixture {

    /** Default width — 1080p landscape. */
    const val DEFAULT_WIDTH = 1920

    /** Default height — 1080p landscape. */
    const val DEFAULT_HEIGHT = 1080

    /** Default frame rate. Matches the iOS sibling. */
    const val DEFAULT_FPS = 30

    /** Default duration in seconds. 60 s × 30 Mbps ≈ 225 MB of H.264 when input is random noise. */
    const val DEFAULT_DURATION_SEC = 60

    /** Default bitrate in bps — deliberately high so random input produces a large output. */
    const val DEFAULT_BITRATE = 30_000_000

    /**
     * Generate a large MP4 at [output] with the given parameters.
     *
     * @throws IllegalArgumentException if any parameter is non-positive.
     * @throws IllegalStateException if encoder/muxer setup fails or encoding stalls.
     */
    @Suppress("LongParameterList")
    fun generate(
        output: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        fps: Int = DEFAULT_FPS,
        durationSec: Int = DEFAULT_DURATION_SEC,
        bitrate: Int = DEFAULT_BITRATE,
    ): File {
        require(width > 0 && height > 0 && fps > 0 && durationSec > 0 && bitrate > 0) {
            "width=$width, height=$height, fps=$fps, durationSec=$durationSec, bitrate=$bitrate must all be > 0"
        }
        output.delete()

        val format = MediaFormat.createVideoFormat(H264_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // One keyframe per second keeps GOPs short enough that random-noise content produces
            // a large bitstream — a long GOP lets H.264 collapse runs of similar frames despite
            // the noise target. Mirrors AVVideoMaxKeyFrameIntervalKey=fps in the iOS sibling.
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
            encodeFrames(encoder, muxer, width, height, fps, durationSec)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
        return output
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun encodeFrames(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Int,
    ) {
        val frameCount = fps * durationSec
        val yuvSize = width * height * YUV_BYTES_PER_PIXEL / YUV_DIVISOR
        val yuvScratch = ByteArray(yuvSize)
        val random = Random(RNG_SEED)
        val info = MediaCodec.BufferInfo()
        var muxerTrack = -1
        var muxerStarted = false
        var framesSubmitted = 0
        val startNanos = System.nanoTime()
        var lastProgressNanos = startNanos

        while (true) {
            val now = System.nanoTime()
            if (now - lastProgressNanos > STALL_TIMEOUT_NANOS) {
                error("LargeMp4Fixture stalled: no encoder progress for ${STALL_TIMEOUT_NANOS / NANOS_PER_SEC}s")
            }
            if (now - startNanos > OVERALL_TIMEOUT_NANOS) {
                error("LargeMp4Fixture overall timeout (${OVERALL_TIMEOUT_NANOS / NANOS_PER_SEC}s) reached")
            }

            if (framesSubmitted <= frameCount) {
                val inputIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIdx >= 0) {
                    lastProgressNanos = System.nanoTime()
                    if (framesSubmitted < frameCount) {
                        random.nextBytes(yuvScratch)
                        val pts = framesSubmitted.toLong() * US_PER_SEC / fps
                        val image = encoder.getInputImage(inputIdx)
                            ?: error("MediaCodec.getInputImage returned null for YUV420Flexible input")
                        writePlanarYuvToImage(image, yuvScratch, width, height)
                        encoder.queueInputBuffer(inputIdx, 0, yuvSize, pts, 0)
                    } else {
                        encoder.queueInputBuffer(
                            inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                    framesSubmitted++
                }
            }

            val outputIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    lastProgressNanos = System.nanoTime()
                }
                outputIdx >= 0 -> {
                    lastProgressNanos = System.nanoTime()
                    val buf = encoder.getOutputBuffer(outputIdx) ?: error("No output buffer")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        muxer.writeSampleData(muxerTrack, buf, info)
                    }
                    encoder.releaseOutputBuffer(outputIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /**
     * Copy planar YUV420 data (Y, U, V, each row-major, no padding) into a MediaCodec input
     * [image] respecting per-plane `pixelStride` and `rowStride`. Handles I420, NV12/NV21, and
     * row-padded variants. Same layout-agnostic strategy as [Mp4Generator.writePlanarYuvToImage]
     * — duplicated here (not factored out) to keep the large-fixture path independent of the
     * tuning constraints that govern `Mp4Generator`.
     */
    private fun writePlanarYuvToImage(
        image: android.media.Image,
        planar: ByteArray,
        width: Int,
        height: Int,
    ) {
        val ySize = width * height
        val uvSize = ySize / UV_PLANE_DIVISOR
        val uvWidth = width / 2
        val uvHeight = height / 2
        copyPlane(image.planes[0], planar, 0, width, height, width)
        copyPlane(image.planes[1], planar, ySize, uvWidth, uvHeight, uvWidth)
        copyPlane(image.planes[2], planar, ySize + uvSize, uvWidth, uvHeight, uvWidth)
    }

    @Suppress("LongParameterList")
    private fun copyPlane(
        plane: android.media.Image.Plane,
        src: ByteArray,
        srcOffset: Int,
        planeWidth: Int,
        planeHeight: Int,
        srcRowSize: Int,
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == planeWidth) {
            buf.position(0)
            buf.put(src, srcOffset, planeWidth * planeHeight)
            return
        }
        if (pixelStride == 1) {
            for (row in 0 until planeHeight) {
                buf.position(row * rowStride)
                buf.put(src, srcOffset + row * srcRowSize, planeWidth)
            }
            return
        }
        for (row in 0 until planeHeight) {
            val dstRowBase = row * rowStride
            val srcRowBase = srcOffset + row * srcRowSize
            for (col in 0 until planeWidth) {
                buf.put(dstRowBase + col * pixelStride, src[srcRowBase + col])
            }
        }
    }

    private const val H264_MIME = "video/avc"
    private const val TIMEOUT_US = 10_000L
    private const val US_PER_SEC = 1_000_000L
    private const val NANOS_PER_SEC = 1_000_000_000L
    private const val YUV_BYTES_PER_PIXEL = 3
    private const val YUV_DIVISOR = 2
    private const val UV_PLANE_DIVISOR = 4

    // Deterministic RNG so fixture bytes are reproducible across runs — useful when a test
    // flakes and we want to diff encoder output between attempts.
    private const val RNG_SEED = 0xC0FFEE_1080L

    // Safety nets: 30 s without progress, 5 min overall. Generating 1800 random 1080p frames
    // and encoding them at 30 Mbps finishes comfortably under a minute on Pixel 6-class
    // hardware, but the FTL 15 min job-level budget means a stalled encoder must surface fast.
    private const val STALL_TIMEOUT_NANOS = 30L * NANOS_PER_SEC
    private const val OVERALL_TIMEOUT_NANOS = 5L * 60L * NANOS_PER_SEC
}
