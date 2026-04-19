/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readAudioMetadata
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Input-robustness sweep for the iOS audio compressor.
 *
 * **MP3 / FLAC input coverage is Android-only**; iOS Kompressor pipeline consumes M4A / WAV /
 * AIF via AVFoundation native extractors — no MP3 / FLAC decoder path on iOS. See the Android
 * mirror (`androidDeviceTest/.../AudioInputRobustnessTest.kt` + `MultiFormatInputTest.kt`) for
 * that coverage.
 */
class AudioInputRobustnessTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-audio-robustness-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun twentyFourBitWav_compressesToAacAtMatchingSampleRate() = runTest {
        val inputPath = testDir + "pcm24.wav"
        writeBytes(
            inputPath,
            WavGenerator.generateWavBytes(
                durationSeconds = 1,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
                bitsPerSample = 24,
            ),
        )
        val outputPath = testDir + "pcm24_out.m4a"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess, "24-bit WAV compression failed: ${result.exceptionOrNull()}")
        val meta = readAudioMetadata(outputPath)
        assertEquals(SAMPLE_RATE_44K, meta.sampleRate)
    }

    @Test
    fun zeroByteInput_failsWithIoFailed() = runTest {
        val inputPath = testDir + "empty.wav"
        // `writeBytes` would panic on a zero-length `ByteArray` via `addressOf(0)` — use
        // NSFileManager directly to create a 0-byte file.
        NSFileManager.defaultManager.createFileAtPath(inputPath, contents = null, attributes = null)
        val outputPath = testDir + "empty_out.m4a"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isFailure, "0-byte input must fail")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is AudioCompressionError.IoFailed,
            "Expected IoFailed for 0-byte input, got ${err::class.simpleName}: ${err.message}",
        )
        assertTrue(!NSFileManager.defaultManager.fileExistsAtPath(outputPath))
    }

    @Test
    fun randomBytes_failsWithUnsupportedSourceFormat() = runTest {
        val inputPath = testDir + "garbage.wav"
        writeBytes(inputPath, Random(seed = 0xDEADBEEF).nextBytes(GARBAGE_SIZE_BYTES))
        val outputPath = testDir + "garbage_out.m4a"

        val result = compressor.compress(inputPath, outputPath, AudioCompressionConfig())

        assertTrue(result.isFailure, "Random-byte input must fail")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        // AVFoundation returns `AV_ERR_UNKNOWN (-11800)` for the iOS simulator on this fixture,
        // which the library maps to `DecodingFailed`; other iOS versions surface
        // `AV_ERR_FILE_FORMAT_NOT_RECOGNIZED (-11828)` which maps to `UnsupportedSourceFormat`.
        // Both signal an unrecognised/unplayable source — accept either typed subtype so the
        // test stays stable across OS versions.
        assertTrue(
            err is AudioCompressionError.UnsupportedSourceFormat ||
                err is AudioCompressionError.DecodingFailed,
            "Expected Unsupported/Decoding typed error, got ${err::class.simpleName}: ${err.message}",
        )
    }

    private companion object {
        const val GARBAGE_SIZE_BYTES = 1024
    }
}
