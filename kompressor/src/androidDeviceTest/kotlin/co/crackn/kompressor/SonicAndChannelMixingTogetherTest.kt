/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.TestConstants.MONO
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_22K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_48K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readAudioTrackInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end device test validating the combined Sonic (resample) + ChannelMixing (downmix) path
 * — 48 kHz stereo WAV compressed to a 22.05 kHz mono AAC track. The plan-level unit coverage
 * lives in `androidHostTest/.../SonicAndChannelMixingPlanTest.kt`.
 */
class SonicAndChannelMixingTogetherTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-sonic-mix-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun fortyEightKStereo_resampledTo22kMono_producesCorrectOutput() = runTest {
        val input = File(tempDir, "48k_stereo.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(
                    durationSeconds = 2,
                    sampleRate = SAMPLE_RATE_48K,
                    channels = STEREO,
                ),
            )
        }
        val output = File(tempDir, "22k_mono.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            AudioCompressionConfig(
                sampleRate = SAMPLE_RATE_22K,
                channels = AudioChannels.MONO,
            ),
        )

        assertTrue(
            result.isSuccess,
            "Combined resample + downmix failed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
        val track = readAudioTrackInfo(output)
        assertEquals(AAC_MIME, track.mime, "Output should be AAC")
        assertEquals(SAMPLE_RATE_22K, track.sampleRate, "Output should be resampled to 22050 Hz")
        assertEquals(MONO, track.channels, "Output should be downmixed to mono")
    }

    private companion object {
        const val AAC_MIME = "audio/mp4a-latm"
    }
}
