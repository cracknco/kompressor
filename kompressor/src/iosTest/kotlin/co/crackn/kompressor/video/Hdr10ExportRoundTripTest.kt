/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.runDeviceOnly
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * End-to-end HDR10 HEVC round-trip on physical iOS hardware.
 *
 * Skipped on the iOS simulator where [AVAssetWriterInput] crashes with an uncatchable
 * NSInvalidArgumentException for HEVC Main10 settings.
 */
class Hdr10ExportRoundTripTest {

    private lateinit var testDir: String
    private val compressor = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "hdr10-rt-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun hdr10HevcRoundTrip_producesValidOutput() = runDeviceOnly(
        "HDR10 HEVC Main10 encoding requires A10+ hardware",
    ) {
        runTest {
            val input = testDir + "in.mp4"
            val output = testDir + "out.mp4"
            Mp4Generator.generateMp4(outputPath = input, width = 64, height = 64, frameCount = 8, fps = 8)

            val progressValues = mutableListOf<Float>()
            val result = compressor.compress(
                inputPath = input,
                outputPath = output,
                config = VideoCompressionConfig(
                    codec = VideoCodec.HEVC,
                    dynamicRange = DynamicRange.HDR10,
                ),
                onProgress = { progressValues.add(it) },
            )

            assertTrue(result.isSuccess, "HDR10 HEVC compression failed: ${result.exceptionOrNull()}")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(output), "Output file must exist")
            assertTrue(fileSize(output) > 0, "Output file must be non-empty")

            val cr = result.getOrThrow()
            assertTrue(cr.outputSize > 0, "CompressionResult.outputSize must be > 0")
            assertTrue(cr.durationMs >= 0, "CompressionResult.durationMs must be >= 0")
            assertTrue(progressValues.isNotEmpty(), "Progress callback must fire at least once")
        }
    }

    @Test
    fun hdr10HevcRoundTrip_progressIsMonotonic() = runDeviceOnly(
        "HDR10 HEVC Main10 encoding requires A10+ hardware",
    ) {
        runTest {
            val input = testDir + "in.mp4"
            val output = testDir + "out.mp4"
            Mp4Generator.generateMp4(outputPath = input, width = 64, height = 64, frameCount = 16, fps = 8)

            val progressValues = mutableListOf<Float>()
            compressor.compress(
                inputPath = input,
                outputPath = output,
                config = VideoCompressionConfig(
                    codec = VideoCodec.HEVC,
                    dynamicRange = DynamicRange.HDR10,
                ),
                onProgress = { progressValues.add(it) },
            )

            for (i in 1 until progressValues.size) {
                assertTrue(
                    progressValues[i] >= progressValues[i - 1],
                    "Progress must be monotonic: ${progressValues[i - 1]} -> ${progressValues[i]}",
                )
            }
        }
    }
}
