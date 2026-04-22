/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.TestConstants.MONO
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readAudioDurationSec
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Large-input regression coverage for iOS audio — the Android counterpart lives in
 * `androidDeviceTest/.../LargeFileCompressionTest.kt`. 10 minutes of mono 44.1 kHz 16-bit WAV
 * (~52 MB raw) exercises the streaming AVAssetReader / AVAssetWriter path without accumulating
 * the entire decode in memory.
 */
class LargeFileCompressionTest {

    private lateinit var tempDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "kompressor-large-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
    }

    @Test
    fun tenMinuteMonoWav_compressesWithDurationPreserved() = runTest(
        timeout = kotlin.time.Duration.parse("PT5M"),
    ) {
        val inputPath = tempDir + "long.wav"
        writeBytes(
            inputPath,
            WavGenerator.generateWavBytes(
                durationSeconds = DURATION_SECONDS,
                sampleRate = SAMPLE_RATE_44K,
                channels = MONO,
            ),
        )
        val outputPath = tempDir + "long.m4a"

        val result = compressor.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(outputPath),
            // Mono source → mono output; iOS rejects mono→stereo upmix upfront.
            AudioCompressionConfig(channels = AudioChannels.MONO),
        )

        assertTrue(result.isSuccess, "Long audio compression failed: ${result.exceptionOrNull()}")
        assertTrue(fileSize(outputPath) > 0)

        val outputDurationSec = readAudioDurationSec(outputPath)
        val drift = abs(outputDurationSec - DURATION_SECONDS.toDouble())
        assertTrue(
            drift <= DURATION_TOLERANCE_SEC,
            "Output duration ${outputDurationSec}s drifts >${DURATION_TOLERANCE_SEC}s from " +
                "input ${DURATION_SECONDS}s (drift=${drift}s)",
        )
    }

    private companion object {
        const val DURATION_SECONDS = 600 // 10 minutes
        const val DURATION_TOLERANCE_SEC = 2.0
    }
}
