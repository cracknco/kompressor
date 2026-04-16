/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

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
        rotationDegrees: Int = 0,
    ): File = encodeMp4(
        output = output,
        width = width,
        height = height,
        frameCount = frameCount,
        fps = fps,
        rotationDegrees = rotationDegrees,
        frameFiller = ::fillVaryingYuv,
        // Gradient fixture's size behaviour has been calibrated (in GoldenVideoTest) against
        // the legacy raw-buffer path. Switching it to the Image API changes the encoder's
        // effective chroma entropy — the linear U/V ramp that wraps many times per row
        // compresses differently once laid out correctly — and blows the MBps-to-size
        // bounds. Keep it on the raw-buffer path; sentinel test below opts into Image API.
        inputViaImageApi = false,
    )

    /**
     * Generate a valid MP4 whose *displayed* frame (after [rotationDegrees] is applied by
     * the player) has coloured corner markers: red at top-left, green at top-right, blue
     * at bottom-left, and black at bottom-right. Used by the rotation sentinel test to
     * catch 90° wrong-way (CW-vs-CCW) rotation bugs that dim-swap assertions miss.
     *
     * The native pixel layout is computed as the inverse of [rotationDegrees] so the
     * player's forward rotation lands each colour in the expected displayed corner.
     */
    fun generateCornerMarkedMp4(
        output: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        frameCount: Int = DEFAULT_FRAME_COUNT,
        fps: Int = DEFAULT_FPS,
        rotationDegrees: Int = 0,
        markerSize: Int = DEFAULT_MARKER_SIZE,
    ): File {
        val normalisedRotation = ((rotationDegrees % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        val corners = nativeCornerPositions(normalisedRotation, width, height, markerSize)
        return encodeMp4(
            output = output,
            width = width,
            height = height,
            frameCount = frameCount,
            fps = fps,
            rotationDegrees = rotationDegrees,
            frameFiller = { dst, w, h, _ -> fillCornerMarkedYuv(dst, w, h, corners, markerSize) },
            inputViaImageApi = true,
        )
    }

    @Suppress("LongParameterList")
    private fun encodeMp4(
        output: File,
        width: Int,
        height: Int,
        frameCount: Int,
        fps: Int,
        rotationDegrees: Int,
        frameFiller: (ByteArray, Int, Int, Int) -> Unit,
        inputViaImageApi: Boolean,
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
        val normalisedRotation = ((rotationDegrees % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        if (normalisedRotation != 0) {
            // Writes into the container's tkhd matrix so downstream decoders / players
            // (and MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) see the tag.
            muxer.setOrientationHint(normalisedRotation)
        }
        try {
            encodeFrames(encoder, muxer, width, height, frameCount, fps, frameFiller, inputViaImageApi)
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
        frameFiller: (ByteArray, Int, Int, Int) -> Unit,
        inputViaImageApi: Boolean,
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
                    if (framesSubmitted < frameCount) {
                        frameFiller(yuvData, width, height, framesSubmitted)
                        val pts = framesSubmitted.toLong() * US_PER_SEC / fps
                        if (inputViaImageApi) {
                            // COLOR_FormatYUV420Flexible does not guarantee a specific planar
                            // layout: devices may expose I420 (pixelStride=1) or NV12/NV21
                            // (interleaved UV, pixelStride=2). Writing raw bytes via
                            // getInputBuffer assumes I420 and silently corrupts chroma on
                            // NV12 encoders (e.g. Pixel 6) — invisible when only dimensions
                            // or size are asserted, but catastrophic for tests asserting
                            // exact pixel colour at exact positions (corner-marker sentinel).
                            val image = encoder.getInputImage(inputIdx)
                                ?: error("MediaCodec.getInputImage returned null for YUV420Flexible input")
                            writePlanarYuvToImage(image, yuvData, width, height)
                            val yuvBytes = width * height * YUV_BYTES_PER_PIXEL / YUV_DIVISOR
                            encoder.queueInputBuffer(inputIdx, 0, yuvBytes, pts, 0)
                        } else {
                            // Legacy raw-buffer path: kept for fixtures whose downstream
                            // assertions (bitrate/size) are calibrated against the chroma
                            // scrambling that NV12-reinterpretation of planar bytes
                            // produces. See generateMp4's caller comment.
                            val inputBuf = encoder.getInputBuffer(inputIdx) ?: error("No input buffer")
                            inputBuf.clear()
                            val written = minOf(yuvData.size, inputBuf.capacity())
                            inputBuf.put(yuvData, 0, written)
                            encoder.queueInputBuffer(inputIdx, 0, written, pts, 0)
                        }
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
     * Copy planar YUV420 data in [planar] (Y then U then V, each row-major with no padding)
     * into a MediaCodec input [image], respecting per-plane `pixelStride` and `rowStride`.
     *
     * Handles I420 (pixelStride=1), NV12/NV21 (pixelStride=2 interleaved UV) and any
     * row-padded variant. Keeping the source in compact planar form lets the corner-marker
     * and gradient fillers stay layout-agnostic.
     */
    private fun writePlanarYuvToImage(image: Image, planar: ByteArray, width: Int, height: Int) {
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
        plane: Image.Plane,
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

    /**
     * Fill [dst] with neutral grey YUV420 plus four [markerSize]x[markerSize] solid-colour
     * blocks at the native corners specified by [corners]. Chroma planes are written at
     * half resolution (YUV420).
     */
    private fun fillCornerMarkedYuv(
        dst: ByteArray,
        width: Int,
        height: Int,
        corners: CornerBlockPositions,
        markerSize: Int,
    ) {
        val ySize = width * height
        val uvSize = ySize / UV_PLANE_DIVISOR
        val uOffset = ySize
        val vOffset = ySize + uvSize
        val uvWidth = width / 2
        // Background: neutral grey (BT.601 limited-range: Y=126, U=V=128).
        dst.fill(BG_Y.toByte(), 0, ySize)
        dst.fill(CHROMA_NEUTRAL.toByte(), ySize, ySize + 2 * uvSize)

        paintYuvBlock(dst, width, uvWidth, uOffset, vOffset, corners.red, markerSize, RED_YUV)
        paintYuvBlock(dst, width, uvWidth, uOffset, vOffset, corners.green, markerSize, GREEN_YUV)
        paintYuvBlock(dst, width, uvWidth, uOffset, vOffset, corners.blue, markerSize, BLUE_YUV)
        paintYuvBlock(dst, width, uvWidth, uOffset, vOffset, corners.black, markerSize, BLACK_YUV)
    }

    @Suppress("LongParameterList")
    private fun paintYuvBlock(
        dst: ByteArray,
        yStride: Int,
        uvStride: Int,
        uOffset: Int,
        vOffset: Int,
        position: Pair<Int, Int>,
        size: Int,
        color: IntArray,
    ) {
        val (x0, y0) = position
        for (dy in 0 until size) {
            val yRow = (y0 + dy) * yStride
            for (dx in 0 until size) {
                dst[yRow + x0 + dx] = color[0].toByte()
            }
        }
        val uvSize = size / 2
        for (dy in 0 until uvSize) {
            val uvRow = (y0 / 2 + dy) * uvStride
            for (dx in 0 until uvSize) {
                val off = uvRow + x0 / 2 + dx
                dst[uOffset + off] = color[1].toByte()
                dst[vOffset + off] = color[2].toByte()
            }
        }
    }

    private data class CornerBlockPositions(
        val red: Pair<Int, Int>,
        val green: Pair<Int, Int>,
        val blue: Pair<Int, Int>,
        val black: Pair<Int, Int>,
    )

    /**
     * Returns the native (pre-rotation) top-left coordinates of the four marker blocks such
     * that, once the container's rotation tag is applied at display time, the displayed
     * frame has red/green/blue/black in top-left, top-right, bottom-left, bottom-right.
     *
     * Uses CW display rotation (matches [MediaMuxer.setOrientationHint]). At 90°, native
     * (0, 0) rotates to displayed top-right, so green — the displayed-top-right marker —
     * sits at native top-left, etc.
     */
    private fun nativeCornerPositions(
        rotation: Int,
        width: Int,
        height: Int,
        markerSize: Int,
    ): CornerBlockPositions {
        val tl = 0 to 0
        val tr = (width - markerSize) to 0
        val bl = 0 to (height - markerSize)
        val br = (width - markerSize) to (height - markerSize)
        return when (rotation) {
            0 -> CornerBlockPositions(red = tl, green = tr, blue = bl, black = br)
            QUARTER_TURN -> CornerBlockPositions(red = bl, green = tl, blue = br, black = tr)
            HALF_CIRCLE -> CornerBlockPositions(red = br, green = bl, blue = tr, black = tl)
            THREE_QUARTER_TURN -> CornerBlockPositions(red = tr, green = br, blue = tl, black = bl)
            else -> error("Unsupported rotation for corner-marker fixture: $rotation")
        }
    }

    private const val H264_MIME = "video/avc"
    private const val DEFAULT_WIDTH = 320
    private const val DEFAULT_HEIGHT = 240
    private const val DEFAULT_FRAME_COUNT = 30
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_BITRATE = 500_000
    private const val DEFAULT_MARKER_SIZE = 16
    private const val TIMEOUT_US = 10_000L
    private const val US_PER_SEC = 1_000_000L
    private const val YUV_BYTES_PER_PIXEL = 3
    private const val YUV_DIVISOR = 2
    private const val UV_PLANE_DIVISOR = 4
    private const val BYTE_MASK = 0xFF
    private const val FULL_CIRCLE = 360
    private const val QUARTER_TURN = 90
    private const val HALF_CIRCLE = 180
    private const val THREE_QUARTER_TURN = 270
    private const val LUMA_FRAME_PHASE = 7
    private const val CHROMA_U_FRAME_PHASE = 3
    private const val CHROMA_V_FRAME_PHASE = 5

    // BT.601 limited-range RGB → YUV (Y in [16..235], U/V in [16..240]). MediaCodec's
    // default for SD content. Values verified against the standard conversion matrix.
    private const val BG_Y = 126
    private const val CHROMA_NEUTRAL = 128
    private val RED_YUV = intArrayOf(81, 90, 240)
    private val GREEN_YUV = intArrayOf(145, 54, 34)
    private val BLUE_YUV = intArrayOf(41, 240, 110)
    private val BLACK_YUV = intArrayOf(16, 128, 128)
}
