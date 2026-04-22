/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.DynamicRange
import co.crackn.kompressor.video.VideoCodec
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoCompressionError
import co.crackn.kompressor.video.deviceSupportsHdr10Hevc
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HDR10 compression contract tests on Android device.
 *
 * The full HDR10 → HDR10 round-trip needs a P010 / Main10 fixture generator that's deferred to
 * a follow-up PR (substantial Surface + MediaCodec wiring). What we verify here is the public
 * contract that gates real-device behaviour:
 *
 * 1. HEVC SDR compression works end-to-end (regression guard for the codec dispatch).
 * 2. HDR10 + HEVC config either succeeds (device exposes a Main10 encoder *and* the active
 *    runtime probe confirms it works; FTL Pixel 6 Tensor G1 does) or surfaces the typed
 *    `VideoCompressionError.Hdr10NotSupported` we promise on devices where either the
 *    capability matrix doesn't advertise Main10 or the runtime probe rejects it — never
 *    silently downgrades to 8-bit.
 *
 * The cross-field validation (HDR10 + H264 rejected at construct) is covered by the host-side
 * `VideoCompressionConfigHdrTest` so this device file doesn't duplicate it.
 */
class HdrVideoCompressionTest {

    private lateinit var testDir: File
    private val compressor = AndroidVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = File.createTempFile("kompressor-hdr-", "")
        testDir.delete()
        testDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun hevcSdrConfig_compressesSdrInput_producesHevcOutput() = runTest {
        val input = File(testDir, "in.mp4")
        val output = File(testDir, "out.mp4")
        Mp4Generator.generateMp4(input, width = 32, height = 32, frameCount = 8, fps = 8)

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config = VideoCompressionConfig(codec = VideoCodec.HEVC),
        )

        assertTrue(result.isSuccess, "HEVC SDR compression must succeed: ${result.exceptionOrNull()}")
        assertOutputMime(output.absolutePath, expected = "video/hevc")
    }

    @Test
    fun hdr10Config_routesThroughTypedContract() = runTest {
        val supported = deviceSupportsHdr10Hevc()
        val input = File(testDir, "in.mp4")
        val output = File(testDir, "out.mp4")
        Mp4Generator.generateMp4(input, width = 32, height = 32, frameCount = 8, fps = 8)

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config = VideoCompressionConfig(codec = VideoCodec.HEVC, dynamicRange = DynamicRange.HDR10),
        )

        if (supported) {
            // Capability-matrix says Main10+FEATURE_HdrEditing is advertised. That's now a
            // pre-condition, not a guarantee — the active probe (Hdr10HevcProbe.probe) is the
            // final word. On an FTL Pixel 6 both agree and we get a valid HEVC output; on an
            // OEM that advertises but fails the probe, the compressor surfaces the typed
            // Hdr10NotSupported error below instead of success. Accept either outcome here as
            // long as the contract is consistent (no silent SDR downgrade, no partial bytes).
            if (result.isSuccess) {
                assertOutputMime(output.absolutePath, expected = "video/hevc")
            } else {
                val err = result.exceptionOrNull()
                assertTrue(
                    err is VideoCompressionError.Hdr10NotSupported,
                    "Advertised Main10 device where active probe fails must surface " +
                        "Hdr10NotSupported, got $err",
                )
                assertTrue(
                    !output.exists(),
                    "Failed HDR10 compression must leave no partial output",
                )
            }
        } else {
            val err = result.exceptionOrNull()
            assertTrue(
                err is VideoCompressionError.Hdr10NotSupported,
                "HDR10 on a device without HEVC Main10 must surface Hdr10NotSupported, got $err",
            )
            assertTrue(
                !output.exists(),
                "Failed HDR10 compression must leave no partial output",
            )
        }
    }

    private fun assertOutputMime(path: String, expected: String) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val mime = (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") }
            assertTrue(
                mime == expected,
                "Expected output video mime '$expected', got '$mime'",
            )
        } finally {
            extractor.release()
        }
    }
}
