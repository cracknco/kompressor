/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.runDeviceOnly
import co.crackn.kompressor.testutil.writeBytes
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
 * 5.1 surround AAC rejection on iOS hardware.
 *
 * Device Farm run 24536970778 (iPhone 13 / A15 / iOS 18) confirmed AudioToolbox rejects
 * multichannel AAC output at every tested bitrate. These tests verify the rejection surfaces
 * as a typed [AudioCompressionError.UnsupportedConfiguration] through the full `compress()` path.
 */
class Surround51RoundTripTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-surround51-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun fivePointOneSurround_rejectedWithUnsupportedConfiguration() = runDeviceOnly(
        "5.1 multi-channel AAC rejection verified on physical iOS hardware",
    ) {
        runTest {
            val inputPath = testDir + "surround51.wav"
            writeBytes(inputPath, WavGenerator.generateWavBytes(DURATION_SEC, SAMPLE_RATE, CHANNELS_51))

            val result = compressor.compress(
                inputPath = inputPath,
                outputPath = testDir + "out.m4a",
                config = AudioCompressionConfig(
                    channels = AudioChannels.FIVE_POINT_ONE,
                    bitrate = BITRATE_51,
                    sampleRate = SAMPLE_RATE,
                ),
            )

            assertTrue(result.isFailure, "5.1 surround must be rejected on iOS")
            val err = result.exceptionOrNull()
            assertTrue(
                err is AudioCompressionError.UnsupportedConfiguration,
                "Expected UnsupportedConfiguration, got ${err?.let { it::class.simpleName }}: ${err?.message}",
            )
        }
    }

    private companion object {
        const val DURATION_SEC = 2
        const val SAMPLE_RATE = 48_000
        const val CHANNELS_51 = 6
        const val BITRATE_51 = 384_000
    }
}
