/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.golden

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.readTopLevelMp4Boxes
import co.crackn.kompressor.testutil.readVideoMetadata
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoPresets
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Golden tests verify that video compression of known inputs produces outputs
 * within expected functional criteria: valid container, correct dimensions,
 * reasonable file sizes, and metadata correctness.
 */
class GoldenVideoTest {

    private lateinit var tempDir: File
    private lateinit var inputFile: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-golden-video").apply { mkdirs() }
        inputFile = Mp4Generator.generateMp4(
            output = File(tempDir, "golden_input.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAMES,
            fps = INPUT_FPS,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun defaultConfig_producesValidMp4WithCorrectDimensions() = runTest {
        val output = File(tempDir, "golden_default.mp4")

        val result = compressor.compress(inputFile.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "Default config failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(output.readBytes()), "Must be valid MP4")

        val metadata = readVideoMetadata(output)
        // Default is 720p — input is already 720p, so no downscale
        assertTrue(metadata.width in (INPUT_WIDTH - 2)..(INPUT_WIDTH + 2), "Width should be ~$INPUT_WIDTH")
        assertTrue(metadata.height in (INPUT_HEIGHT - 2)..(INPUT_HEIGHT + 2), "Height should be ~$INPUT_HEIGHT")

        // Structural gate: same tight-muxer check as the audio golden. A regression that drops
        // the muxer factory override would reintroduce Media3's default 400 KB `moov`
        // reservation as a top-level `free` box.
        val freeBoxTotal = readTopLevelMp4Boxes(output).filter { it.type == "free" }.sumOf { it.size }
        assertTrue(
            freeBoxTotal < MAX_FREE_BOX_BYTES,
            "Top-level `free` boxes total $freeBoxTotal B — the tight-muxer override must keep " +
                "this below $MAX_FREE_BOX_BYTES B (Media3 default reserves 400 000 B)",
        )
    }

    @Test
    fun lowBandwidthPreset_downscalesTo480p() = runTest {
        val output = File(tempDir, "golden_low.mp4")

        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            VideoPresets.LOW_BANDWIDTH,
        )

        assertTrue(result.isSuccess, "LOW_BANDWIDTH failed: ${result.exceptionOrNull()}")
        val metadata = readVideoMetadata(output)
        val shortEdge = minOf(metadata.width, metadata.height)
        assertTrue(shortEdge <= MAX_480P_SHORT, "Short edge $shortEdge should be <= $MAX_480P_SHORT")
    }

    @Test
    fun highQualityPreset_preservesResolution() = runTest {
        val output = File(tempDir, "golden_high.mp4")

        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            VideoPresets.HIGH_QUALITY,
        )

        assertTrue(result.isSuccess, "HIGH_QUALITY failed: ${result.exceptionOrNull()}")
        val metadata = readVideoMetadata(output)
        // Input is 1280x720, HIGH_QUALITY is 1080p — no upscale, should keep original
        assertTrue(metadata.width in (INPUT_WIDTH - 2)..(INPUT_WIDTH + 2))
        assertTrue(metadata.height in (INPUT_HEIGHT - 2)..(INPUT_HEIGHT + 2))
    }

    @Test
    fun lowerBitrate_producesSmaller() = runTest {
        val outputLow = File(tempDir, "golden_bitrate_low.mp4")
        val outputHigh = File(tempDir, "golden_bitrate_high.mp4")

        val lowResult = compressor.compress(
            inputFile.absolutePath,
            outputLow.absolutePath,
            VideoCompressionConfig(videoBitrate = 300_000),
        )
        val highResult = compressor.compress(
            inputFile.absolutePath,
            outputHigh.absolutePath,
            VideoCompressionConfig(videoBitrate = 3_000_000),
        )

        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)
        assertTrue(
            outputLow.length() < outputHigh.length(),
            "300kbps (${outputLow.length()}) should be < 3Mbps (${outputHigh.length()})",
        )
    }

    @Test
    fun outputSizeWithinExpectedRange() = runTest {
        val output = File(tempDir, "golden_size.mp4")

        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            VideoCompressionConfig(videoBitrate = 1_200_000),
        )

        assertTrue(result.isSuccess)
        // 1s at 1.2Mbps ≈ 150KB, with wide margins for hardware encoder variance
        assertTrue(
            output.length() in EXPECTED_MIN_SIZE..EXPECTED_MAX_SIZE,
            "1s at 1.2Mbps: expected $EXPECTED_MIN_SIZE-$EXPECTED_MAX_SIZE, got ${output.length()}",
        )
    }

    private companion object {
        const val INPUT_WIDTH = 1280
        const val INPUT_HEIGHT = 720
        const val INPUT_FRAMES = 30
        const val INPUT_FPS = 30
        const val MAX_480P_SHORT = 482
        const val EXPECTED_MIN_SIZE = 10_000L // Very generous lower bound
        const val EXPECTED_MAX_SIZE = 500_000L // Very generous upper bound

        // Tight-muxer factory override keeps aggregate top-level `free` padding ~9 B per box.
        // 128 B is strict enough to catch any padding regression while leaving a small margin.
        const val MAX_FREE_BOX_BYTES = 128L
    }
}
