/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import co.crackn.kompressor.testutil.Hdr10ColorMath
import co.crackn.kompressor.testutil.Hdr10Mp4Generator
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.DynamicRange
import co.crackn.kompressor.video.VideoCodec
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.deviceSupportsHdr10Hevc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * HDR10 round-trip pixel-fidelity test (CRA-6).
 *
 * The HDR10 contract guarded by `HdrVideoCompressionTest` only checks the *structural* promise
 * (right codec chosen, typed error when unsupported). A regression in the colour matrix
 * (e.g. BT.2020 → BT.709 downconversion, wrong transfer function, or accidental tone-map to
 * SDR) would still pass that test while silently corrupting every user's HDR source.
 *
 * This test closes that gap:
 *
 *   1. Generate a fresh HDR10 fixture on-device with [Hdr10Mp4Generator] — four canonical
 *      BT.2020 primary patches (red, green, blue, D65 white), clamped to 1000 cd/m², written
 *      directly as 10-bit P010 samples through the HEVC Main10 encoder.
 *   2. Compress the fixture through the production [AndroidVideoCompressor] HDR10 path.
 *   3. Decode the output in 10-bit P010 buffer mode, sample a 16×16 luma patch at the centre
 *      of each quadrant (plus the matching 8×8 chroma patch), convert through the inverse
 *      narrow-range → BT.2020 ncl → XYZ → L*a*b* pipeline, and check CIEDE2000 ΔE00 ≤ 2
 *      against the encoder's input patches.
 *   4. Confirm `KEY_COLOR_STANDARD` / `KEY_COLOR_TRANSFER` / `KEY_COLOR_RANGE` survive the
 *      round-trip (Media3 must write the BT.2020 ncl / ST.2084 / narrow-range VUI).
 *
 * The test skips when the device lacks a Main10 HDR10 encoder or when the matching decoder
 * cannot deliver P010 in buffer mode — both are honest signals rather than a false-pass.
 */
class Hdr10PixelFidelityRoundTripTest {

    private lateinit var testDir: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        testDir = File.createTempFile("kompressor-hdr10-roundtrip-", "")
        testDir.delete()
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun hdr10RoundTrip_preservesBt2020PrimariesAndColorMetadata() = runTest {
        assumeTrue(
            "Device lacks HEVC Main10 HDR10 encoder — cannot exercise HDR10 round-trip",
            deviceSupportsHdr10Hevc(),
        )

        val fixture = File(testDir, "hdr10_p010.mp4").also { Hdr10Mp4Generator.generate(it) }
        assertTrue(
            fixture.length() <= MAX_FIXTURE_BYTES,
            "HDR10 fixture must stay under ${MAX_FIXTURE_BYTES / BYTES_PER_MEGABYTE} MB " +
                "(was ${fixture.length()} B)",
        )

        val output = File(testDir, "hdr10_out.mp4")
        val config = VideoCompressionConfig(
            codec = VideoCodec.HEVC,
            dynamicRange = DynamicRange.HDR10,
        )
        val result = compressor.compress(fixture.absolutePath, output.absolutePath, config)
        assertTrue(
            result.isSuccess,
            "HDR10 round-trip compression must succeed: ${result.exceptionOrNull()}",
        )

        assertColorMetadataPreserved(output.absolutePath)
        assertPrimariesRoundTrip(output.absolutePath)
    }

    private fun assertColorMetadataPreserved(path: String) {
        val format = videoTrackFormat(path)
        assertColorKey(format, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        assertColorKey(format, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        assertColorKey(format, MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    private fun assertColorKey(format: MediaFormat, key: String, expected: Int) {
        assertTrue(
            format.containsKey(key),
            "Output MediaFormat missing $key — color VUI was not preserved after HDR10 round-trip",
        )
        val actual = format.getInteger(key)
        assertTrue(
            actual == expected,
            "$key mismatch after round-trip: expected $expected, got $actual " +
                "(regressed to a different colour space / transfer / range)",
        )
    }

    private fun assertPrimariesRoundTrip(path: String) {
        val samples = decodeAndSampleMidFrame(path)
        val deltaE = mutableMapOf<String, Double>()
        deltaE["red"] = deltaE00(samples.red, Hdr10Mp4Generator.PATCH_RED)
        deltaE["green"] = deltaE00(samples.green, Hdr10Mp4Generator.PATCH_GREEN)
        deltaE["blue"] = deltaE00(samples.blue, Hdr10Mp4Generator.PATCH_BLUE)
        deltaE["white"] = deltaE00(samples.white, Hdr10Mp4Generator.PATCH_WHITE)
        deltaE.forEach { (name, de) ->
            assertTrue(
                de <= DELTA_E_TOLERANCE,
                "BT.2020 $name primary drifted ΔE=$de (> $DELTA_E_TOLERANCE) after HDR10 " +
                    "round-trip — colour matrix or transfer may have regressed",
            )
        }
    }

    private fun deltaE00(measured: Hdr10Mp4Generator.PatchYuv10, expected: Hdr10Mp4Generator.PatchYuv10): Double {
        val measuredLab = Hdr10ColorMath.yuv10ToLab(measured.y, measured.cb, measured.cr)
        val expectedLab = Hdr10ColorMath.yuv10ToLab(expected.y, expected.cb, expected.cr)
        return Hdr10ColorMath.deltaE2000(measuredLab, expectedLab)
    }

    private fun videoTrackFormat(path: String): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return f
            }
            error("No video track found in $path")
        } finally {
            extractor.release()
        }
    }

    private data class QuadrantSamples(
        val red: Hdr10Mp4Generator.PatchYuv10,
        val green: Hdr10Mp4Generator.PatchYuv10,
        val blue: Hdr10Mp4Generator.PatchYuv10,
        val white: Hdr10Mp4Generator.PatchYuv10,
    )

    private fun decodeAndSampleMidFrame(path: String): QuadrantSamples {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val trackIdx = (0 until extractor.trackCount).first { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val inputFormat = extractor.getTrackFormat(trackIdx).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
            )
        }
        extractor.selectTrack(trackIdx)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("track has no mime")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        try {
            return drainDecoderAndSample(extractor, decoder)
        } finally {
            try { decoder.stop() } catch (_: IllegalStateException) { /* already stopped */ }
            decoder.release()
            extractor.release()
        }
    }

    private fun drainDecoderAndSample(
        extractor: MediaExtractor,
        decoder: MediaCodec,
    ): QuadrantSamples {
        val info = MediaCodec.BufferInfo()
        val frames = mutableListOf<QuadrantSamples>()
        var width = 0
        var height = 0
        var endOfInput = false
        var endOfOutput = false

        while (!endOfOutput) {
            endOfInput = feedDecoder(extractor, decoder, endOfInput)
            val outIdx = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = decoder.outputFormat
                    val colorFormat = outFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    assumeTrue(
                        "Decoder did not negotiate P010 output (got $colorFormat) — skipping " +
                            "pixel-fidelity check on this hardware",
                        colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
                    )
                    width = outFormat.getInteger(MediaFormat.KEY_WIDTH)
                    height = outFormat.getInteger(MediaFormat.KEY_HEIGHT)
                }
                outIdx >= 0 -> {
                    if (info.size > 0 && width > 0 && height > 0) {
                        val buf = decoder.getOutputBuffer(outIdx) ?: error("No output buffer")
                        frames += sampleQuadrants(buf, width, height)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) endOfOutput = true
                }
            }
        }
        assertTrue(frames.isNotEmpty(), "Decoder emitted no frames — cannot sample pixels")
        return frames[frames.size / 2]
    }

    private fun feedDecoder(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        alreadyEnded: Boolean,
    ): Boolean {
        if (alreadyEnded) return true
        val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIdx < 0) return false
        val inBuf = decoder.getInputBuffer(inIdx) ?: error("No input buffer")
        val sampleSize = extractor.readSampleData(inBuf, 0)
        if (sampleSize < 0) {
            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /**
     * Sample the centre of each quadrant from a P010 buffer (high-10-bits-in-LE-16 layout,
     * Y plane followed by interleaved UV at ½×½ resolution). Averages a 16×16 luma block and
     * its matching 8×8 chroma block so we're not at the mercy of one possibly-on-a-block-edge
     * pixel — block artefacts near quadrant boundaries are normal even on the reference encoder.
     */
    private fun sampleQuadrants(buf: ByteBuffer, width: Int, height: Int): QuadrantSamples {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val quartW = width / 4
        val quartH = height / 4
        return QuadrantSamples(
            red = samplePatch(buf, width, height, quartW, quartH),
            green = samplePatch(buf, width, height, 3 * quartW, quartH),
            blue = samplePatch(buf, width, height, quartW, 3 * quartH),
            white = samplePatch(buf, width, height, 3 * quartW, 3 * quartH),
        )
    }

    private fun samplePatch(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
    ): Hdr10Mp4Generator.PatchYuv10 {
        val y = averageLuma(buf, width, centerX, centerY)
        val (cb, cr) = averageChroma(buf, width, height, centerX, centerY)
        return Hdr10Mp4Generator.PatchYuv10(y, cb, cr)
    }

    private fun averageLuma(buf: ByteBuffer, width: Int, centerX: Int, centerY: Int): Int {
        var sum = 0L
        var count = 0
        for (dy in -LUMA_HALF_WINDOW until LUMA_HALF_WINDOW) {
            for (dx in -LUMA_HALF_WINDOW until LUMA_HALF_WINDOW) {
                val y = centerY + dy
                val x = centerX + dx
                val byteOffset = (y * width + x) * P010_BYTES_PER_SAMPLE
                val sample16 = buf.getShort(byteOffset).toInt() and UINT16_MASK
                sum += sample16 ushr P010_SHIFT
                count++
            }
        }
        return (sum / count).toInt()
    }

    private fun averageChroma(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
    ): Pair<Int, Int> {
        val uvPlaneOffset = width * height * P010_BYTES_PER_SAMPLE
        val uvStrideBytes = width * P010_BYTES_PER_SAMPLE // (W/2) pairs × 2 B/sample × 2 samples
        val chromaCx = centerX / 2
        val chromaCy = centerY / 2
        var cbSum = 0L
        var crSum = 0L
        var count = 0
        for (dy in -CHROMA_HALF_WINDOW until CHROMA_HALF_WINDOW) {
            for (dx in -CHROMA_HALF_WINDOW until CHROMA_HALF_WINDOW) {
                val cy = chromaCy + dy
                val cx = chromaCx + dx
                val base = uvPlaneOffset + cy * uvStrideBytes + cx * P010_UV_PAIR_BYTES
                val cb16 = buf.getShort(base).toInt() and UINT16_MASK
                val cr16 = buf.getShort(base + P010_BYTES_PER_SAMPLE).toInt() and UINT16_MASK
                cbSum += cb16 ushr P010_SHIFT
                crSum += cr16 ushr P010_SHIFT
                count++
            }
        }
        return (cbSum / count).toInt() to (crSum / count).toInt()
    }

    private companion object {
        const val DELTA_E_TOLERANCE = 2.0
        const val MAX_FIXTURE_BYTES = 1_048_576L
        const val BYTES_PER_MEGABYTE = 1_048_576L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val LUMA_HALF_WINDOW = 8  // 16×16 luma patch
        const val CHROMA_HALF_WINDOW = 4 // matching 8×8 chroma patch
        const val P010_BYTES_PER_SAMPLE = 2
        const val P010_UV_PAIR_BYTES = 4 // Cb(2) + Cr(2) per chroma location
        const val P010_SHIFT = 6
        const val UINT16_MASK = 0xFFFF
    }
}
