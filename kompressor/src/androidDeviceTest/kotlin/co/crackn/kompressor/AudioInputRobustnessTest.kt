/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readAudioMetadata
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Input-robustness sweep for the Android audio compressor — exercises edge-case WAV bit depths,
 * empty files, and random-byte inputs. MP3 / FLAC coverage is handled by the existing
 * `MultiFormatInputTest`; VBR MP3 / FLAC-with-cover-art require external encoders and are
 * deferred to a later fixture-prep PR.
 */
class AudioInputRobustnessTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-audio-robustness-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun twentyFourBitWav_compressesToAacAtMatchingSampleRate() = runTest {
        val input = File(tempDir, "pcm24.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(
                    durationSeconds = 1,
                    sampleRate = SAMPLE_RATE_44K,
                    channels = STEREO,
                    bitsPerSample = 24,
                ),
            )
        }
        val output = File(tempDir, "pcm24_out.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess, "24-bit WAV compression failed: ${result.exceptionOrNull()}")
        assertTrue(output.exists())
        val meta = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_44K, meta.sampleRate, "Output sample rate should match source")
    }

    @Test
    fun zeroByteInput_failsWithIoFailed() = runTest {
        val input = File(tempDir, "empty.wav").apply { writeBytes(ByteArray(0)) }
        val output = File(tempDir, "empty_out.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isFailure, "0-byte input must fail")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is AudioCompressionError.IoFailed,
            "Expected IoFailed for 0-byte input, got ${err::class.simpleName}: ${err.message}",
        )
        assertTrue(!output.exists(), "No output file should be produced for empty input")
    }

    @Test
    fun randomBytes_failsWithUnsupportedSourceFormat() = runTest {
        val input = File(tempDir, "garbage.wav").apply {
            // Deterministic random bytes so failures can be reproduced locally.
            writeBytes(Random(seed = 0xDEADBEEF).nextBytes(GARBAGE_SIZE_BYTES))
        }
        val output = File(tempDir, "garbage_out.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            AudioCompressionConfig(),
        )

        assertTrue(result.isFailure, "Random-byte input must fail")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is AudioCompressionError.UnsupportedSourceFormat,
            "Expected UnsupportedSourceFormat, got ${err::class.simpleName}: ${err.message}",
        )
    }

    private companion object {
        const val GARBAGE_SIZE_BYTES = 1024
    }
}
