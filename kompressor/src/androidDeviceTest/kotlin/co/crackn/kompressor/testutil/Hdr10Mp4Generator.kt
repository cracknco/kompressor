/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Generates a minimal HDR10 HEVC Main10 MP4 with canonical BT.2020 colour patches.
 *
 * The generated file is a 2-second 1920×1080 clip at 30 fps that encodes four solid BT.2020
 * primaries in quadrants:
 *
 * ```
 *   +-------+--------+
 *   |  RED  | GREEN  |
 *   +-------+--------+
 *   | BLUE  | WHITE  |
 *   +-------+--------+
 * ```
 *
 * Each patch is clamped to 1000 cd/m² linear luminance (the `MaxCLL` we advertise via
 * `KEY_HDR_STATIC_INFO`). Pixels are written directly in 10-bit `COLOR_FormatYUVP010` buffer
 * mode — buffer mode is preferred over `MediaCodec.createInputSurface()` here because EGL
 * BT.2020/PQ extensions are fragile across GPUs, whereas a direct P010 write gives
 * deterministic, bit-exact 10-bit samples which is what a pixel-fidelity round-trip test
 * requires.
 *
 * Caller is expected to pre-flight `deviceSupportsHdr10Hevc()`; the generator fails with a
 * clear message if no `MediaCodecList` entry can encode the requested format.
 */
object Hdr10Mp4Generator {

    /** Canonical BT.2020 + PQ colour patch, quantised to 10-bit narrow-range Y'CbCr. */
    data class PatchYuv10(val y: Int, val cb: Int, val cr: Int)

    /**
     * Generate a fresh HDR10 Main10 P010 MP4 at [output]. Returns [output] for chaining.
     * Defaults match the `hdr10_p010.mp4` fixture contract (2s, 1080p, 30 fps, ~2 Mbps).
     */
    @Suppress("LongParameterList")
    fun generate(
        output: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        durationSeconds: Int = DEFAULT_DURATION_SECONDS,
        fps: Int = DEFAULT_FPS,
        bitrate: Int = DEFAULT_BITRATE,
    ): File {
        require(width % 2 == 0 && height % 2 == 0) {
            "P010 requires even dimensions, got ${width}x$height"
        }

        val format = buildHdr10Format(width, height, fps, bitrate)
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error(
                "No HEVC Main10 HDR10 encoder on this device — use deviceSupportsHdr10Hevc() " +
                    "to pre-flight before invoking the generator",
            )
        val encoder = MediaCodec.createByCodecName(encoderName)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val frame = buildCanonicalP010Frame(width, height)
            val totalFrames = durationSeconds * fps
            encodeFrames(encoder, muxer, frame, fps, totalFrames)
        } finally {
            try { encoder.stop() } catch (_: IllegalStateException) { /* already stopped */ }
            encoder.release()
            try { muxer.stop() } catch (_: IllegalStateException) { /* never started */ }
            muxer.release()
        }
        return output
    }

    /**
     * Canonical 10-bit Y'CbCr values for each patch, exposed for tests that need to compute the
     * "expected" colour after round-trip. These are derived from linear BT.2020 primaries at
     * 1000 cd/m² → ST.2084 PQ → BT.2020 ncl → narrow-range 10-bit quantisation — the exact
     * pipeline the generator writes into the encoder's input buffers.
     */
    val PATCH_RED: PatchYuv10 = encodeBt2020Primary(R_LIN, 0.0, 0.0)
    /** Canonical encoding of the BT.2020 green primary at 1000 cd/m². See [PATCH_RED]. */
    val PATCH_GREEN: PatchYuv10 = encodeBt2020Primary(0.0, G_LIN, 0.0)
    /** Canonical encoding of the BT.2020 blue primary at 1000 cd/m². See [PATCH_RED]. */
    val PATCH_BLUE: PatchYuv10 = encodeBt2020Primary(0.0, 0.0, B_LIN)
    /** Canonical encoding of BT.2020 D65 white at 1000 cd/m² (R = G = B = 1.0). See [PATCH_RED]. */
    val PATCH_WHITE: PatchYuv10 = encodeBt2020Primary(R_LIN, G_LIN, B_LIN)

    /**
     * Build the [MediaFormat] the encoder is configured with. Exposed so tests can
     * cross-check the colour keys we *advertise* against what the compressor preserves.
     */
    fun buildHdr10Format(width: Int, height: Int, fps: Int, bitrate: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
            )
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10HDR10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel4)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, buildHdrStaticInfo())
        }

    private fun encodeFrames(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        frameBytes: ByteArray,
        fps: Int,
        totalFrames: Int,
    ) {
        val info = MediaCodec.BufferInfo()
        var muxerTrack = -1
        var muxerStarted = false
        var submitted = 0

        while (true) {
            if (submitted <= totalFrames) {
                val inputIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIdx >= 0) {
                    submitted += submitFrame(encoder, inputIdx, frameBytes, submitted, totalFrames, fps)
                }
            }
            val outputIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIdx >= 0 -> {
                    val buf = encoder.getOutputBuffer(outputIdx) ?: error("No output buffer")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) muxer.writeSampleData(muxerTrack, buf, info)
                    encoder.releaseOutputBuffer(outputIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun submitFrame(
        encoder: MediaCodec,
        inputIdx: Int,
        frameBytes: ByteArray,
        submitted: Int,
        totalFrames: Int,
        fps: Int,
    ): Int {
        if (submitted == totalFrames) {
            encoder.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return 1
        }
        val inputBuf = encoder.getInputBuffer(inputIdx) ?: error("No input buffer at idx=$inputIdx")
        require(inputBuf.capacity() >= frameBytes.size) {
            "Encoder input buffer (${inputBuf.capacity()} B) smaller than P010 frame " +
                "(${frameBytes.size} B) — cannot write a complete frame"
        }
        inputBuf.clear()
        inputBuf.put(frameBytes)
        val pts = submitted.toLong() * US_PER_SEC / fps
        encoder.queueInputBuffer(inputIdx, 0, frameBytes.size, pts, 0)
        return 1
    }

    /**
     * Build a single P010 frame with four quadrants (R / G / B / W). Same bytes are re-used for
     * every frame in the clip — HEVC inter-prediction encodes the static motion as near-zero
     * residuals, so the final file is tiny (<< 1 MB) regardless of bitrate target.
     */
    private fun buildCanonicalP010Frame(width: Int, height: Int): ByteArray {
        val red = PATCH_RED
        val green = PATCH_GREEN
        val blue = PATCH_BLUE
        val white = PATCH_WHITE
        val halfH = height / 2
        val halfW = width / 2

        // Total P010 size: Y plane (2 B/sample) + UV plane (2 B/sample at ½×½ resolution)
        //   = 2·W·H + 2·(W/2)·(H/2)·2  = 2·W·H + W·H  = 3·W·H bytes
        val frame = ByteArray(width * height * BYTES_PER_P010_FRAME_UNIT)
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        // Y plane
        for (y in 0 until height) {
            val top = y < halfH
            for (x in 0 until width) {
                val left = x < halfW
                val patch = when {
                    top && left -> red
                    top -> green
                    left -> blue
                    else -> white
                }
                bb.putShort((patch.y shl P010_SHIFT).toShort())
            }
        }
        // UV plane — NV12-style interleaving at half resolution. Chroma row y covers luma rows
        // 2y..2y+1, so "top half" flips at y = halfH / 2.
        for (y in 0 until halfH) {
            val top = y < halfH / 2
            for (x in 0 until halfW) {
                val left = x < halfW / 2
                val patch = when {
                    top && left -> red
                    top -> green
                    left -> blue
                    else -> white
                }
                bb.putShort((patch.cb shl P010_SHIFT).toShort())
                bb.putShort((patch.cr shl P010_SHIFT).toShort())
            }
        }
        return frame
    }

    /**
     * Apply ST.2084 PQ + BT.2020 ncl + narrow-range 10-bit quantisation to a linear-light
     * BT.2020 RGB triple. Each linear component is assumed to be already normalised against
     * the 10 000 cd/m² PQ peak (so R_LIN = 0.1 ⇒ 1000 cd/m² physical luminance).
     */
    @Suppress("LocalVariableName")
    private fun encodeBt2020Primary(rLin: Double, gLin: Double, bLin: Double): PatchYuv10 {
        val rP = pqOetf(rLin)
        val gP = pqOetf(gLin)
        val bP = pqOetf(bLin)
        val yP = BT2020_YR * rP + BT2020_YG * gP + BT2020_YB * bP
        val cbP = (bP - yP) / BT2020_CB_DENOM
        val crP = (rP - yP) / BT2020_CR_DENOM
        val y10 = (Y_SCALE * yP + Y_OFFSET).roundToInt().coerceIn(0, TEN_BIT_MAX)
        val cb10 = (C_SCALE * cbP + C_OFFSET).roundToInt().coerceIn(0, TEN_BIT_MAX)
        val cr10 = (C_SCALE * crP + C_OFFSET).roundToInt().coerceIn(0, TEN_BIT_MAX)
        return PatchYuv10(y10, cb10, cr10)
    }

    /**
     * ST.2084 PQ OETF: linear luminance in `[0, 1]` (against 10 000 cd/m² peak) → non-linear
     * `E'` in `[0, 1]`. Constants per Rec. ITU-R BT.2100-2 Table 4.
     */
    private fun pqOetf(yLin: Double): Double {
        val y = yLin.coerceIn(0.0, 1.0)
        val yM1 = y.pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * yM1) / (1.0 + PQ_C3 * yM1)).pow(PQ_M2)
    }

    /**
     * Build the 25-byte HDR static info payload that `MediaFormat.KEY_HDR_STATIC_INFO`
     * consumes (ANSI/CTA-861-G + SMPTE ST 2086). Layout follows `HDRStaticInfo::Type1`
     * in AOSP `frameworks/av/media/libstagefright/foundation/ColorUtils.cpp`.
     */
    private fun buildHdrStaticInfo(): ByteBuffer {
        val buf = ByteBuffer.allocate(HDR_STATIC_INFO_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(HDR10_DESCRIPTOR_ID)
        // Mastering display primaries + white point (chromaticity × 50 000 packed as LE uint16).
        // Values > 32 767 overflow a signed Short, so each raw Int is narrowed via `.toShort()`;
        // the low 16 bits land in the buffer verbatim, which matches uint16 little-endian wire
        // format the AOSP `HDRStaticInfo::Type1` struct expects.
        putU16(buf, R_X_U16); putU16(buf, R_Y_U16)
        putU16(buf, G_X_U16); putU16(buf, G_Y_U16)
        putU16(buf, B_X_U16); putU16(buf, B_Y_U16)
        putU16(buf, W_X_U16); putU16(buf, W_Y_U16)
        putU16(buf, MAX_MASTERING_LUM_CDM2)   // 1 cd/m² units
        putU16(buf, MIN_MASTERING_LUM_0K1)    // 0.0001 cd/m² units
        putU16(buf, MAX_CONTENT_LIGHT_LEVEL)
        putU16(buf, MAX_FRAME_AVG_LIGHT_LEVEL)
        buf.flip()
        return buf
    }

    private fun putU16(buf: ByteBuffer, valueU16: Int) {
        require(valueU16 in 0..UINT16_MAX) { "value $valueU16 out of uint16 range" }
        buf.putShort(valueU16.toShort())
    }

    // --- Encoder configuration defaults ------------------------------------------------
    /** Default width of the generated fixture (1080p). */
    const val DEFAULT_WIDTH: Int = 1920
    /** Default height of the generated fixture (1080p). */
    const val DEFAULT_HEIGHT: Int = 1080
    /** Default clip length in seconds (2 s per CRA-6 DoD). */
    const val DEFAULT_DURATION_SECONDS: Int = 2
    /** Default frame rate. */
    const val DEFAULT_FPS: Int = 30
    /** Default target bitrate (2 Mbps; static quadrants encode to ≪ this). */
    const val DEFAULT_BITRATE: Int = 2_000_000

    private const val MIME_HEVC = "video/hevc"
    private const val IFRAME_INTERVAL_SECONDS = 10 // one keyframe across the 2-second clip
    private const val TIMEOUT_US = 10_000L
    private const val US_PER_SEC = 1_000_000L

    // --- P010 layout constants ---------------------------------------------------------
    private const val BYTES_PER_P010_FRAME_UNIT = 3 // per-pixel bytes including ½-res UV
    private const val P010_SHIFT = 6                 // 10-bit sample in high 10 bits of LE16

    // --- BT.2020 ncl matrix + 10-bit narrow-range quantiser ---------------------------
    private const val BT2020_YR = 0.2627
    private const val BT2020_YG = 0.6780
    private const val BT2020_YB = 0.0593
    private const val BT2020_CB_DENOM = 1.8814
    private const val BT2020_CR_DENOM = 1.4746
    private const val Y_SCALE = 876.0  // 219 × 4 (n=10 narrow-range Y' digital step)
    private const val Y_OFFSET = 64.0  // 16  × 4 (black level in 10-bit)
    private const val C_SCALE = 896.0  // 224 × 4 (n=10 narrow-range Cb/Cr step)
    private const val C_OFFSET = 512.0 // 128 × 4 (achromatic midpoint in 10-bit)
    private const val TEN_BIT_MAX = 1023

    // --- Linear BT.2020 component values for 1000 cd/m² against 10 000 cd/m² peak -----
    private const val R_LIN = 0.1
    private const val G_LIN = 0.1
    private const val B_LIN = 0.1

    // --- PQ EOTF constants (BT.2100-2 Table 4) ----------------------------------------
    private const val PQ_M1 = 0.1593017578125
    private const val PQ_M2 = 78.84375
    private const val PQ_C1 = 0.8359375
    private const val PQ_C2 = 18.8515625
    private const val PQ_C3 = 18.6875

    // --- HDR static info (SMPTE ST 2086 + MaxCLL/FALL) --------------------------------
    private const val HDR_STATIC_INFO_BYTES = 25
    private const val HDR10_DESCRIPTOR_ID: Byte = 0
    private const val UINT16_MAX = 0xFFFF
    private const val R_X_U16 = 35400 // 0.708 × 50 000
    private const val R_Y_U16 = 14600 // 0.292 × 50 000
    private const val G_X_U16 = 8500  // 0.170 × 50 000
    private const val G_Y_U16 = 39850 // 0.797 × 50 000
    private const val B_X_U16 = 6550  // 0.131 × 50 000
    private const val B_Y_U16 = 2300  // 0.046 × 50 000
    private const val W_X_U16 = 15635 // 0.3127 × 50 000
    private const val W_Y_U16 = 16450 // 0.3290 × 50 000
    private const val MAX_MASTERING_LUM_CDM2 = 1000
    private const val MIN_MASTERING_LUM_0K1 = 100 // 0.01 cd/m² in 0.0001 units
    private const val MAX_CONTENT_LIGHT_LEVEL = 1000
    private const val MAX_FRAME_AVG_LIGHT_LEVEL = 400
}
