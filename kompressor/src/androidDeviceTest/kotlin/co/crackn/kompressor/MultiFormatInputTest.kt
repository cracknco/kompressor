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
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.copyResourceToCache
import co.crackn.kompressor.testutil.readAudioDurationMs
import co.crackn.kompressor.testutil.readAudioTrackInfo
import kotlin.math.abs
import kotlin.test.assertEquals
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round-trip coverage for the audio input formats the plan explicitly promises support for
 * but that the existing test suite never exercised: MP3, FLAC, OGG/Opus. Previously the
 * non-AAC / non-WAV paths relied entirely on Media3's extractor tests upstream — a
 * regression in how we hand Media3 one of these formats (probe returning null, wrong mime
 * mapping, unexpected channel count) would only surface in user reports.
 *
 * Fixtures are pre-built via ffmpeg (1 s 440 Hz sine, small enough to ship inline):
 *   * `sample_mono_22k.mp3`  — MPEG-1 Layer III, mono, 22.05 kHz, 64 kbps
 *   * `sample_mono_22k.flac` — FLAC mono, 22.05 kHz
 *   * `sample_mono_24k.ogg`  — Opus mono, 24 kHz, 32 kbps
 *
 * AMR-NB is covered separately on-device in `AmrInputTest` because MediaCodec ships an AMR
 * encoder on all Android devices — no binary asset needed.
 */
class MultiFormatInputTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-multi-format-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun mp3Input_compressesToAacWithMatchingConfig() = runTest {
        assertRoundTripMatchesConfig(
            resourceName = "sample_mono_22k.mp3",
            config = AudioCompressionConfig(bitrate = 64_000, sampleRate = 22_050, channels = AudioChannels.MONO),
        )
    }

    @Test
    fun flacInput_compressesToAacWithMatchingConfig() = runTest {
        assertRoundTripMatchesConfig(
            resourceName = "sample_mono_22k.flac",
            config = AudioCompressionConfig(bitrate = 64_000, sampleRate = 22_050, channels = AudioChannels.MONO),
        )
    }

    @Test
    fun oggOpusInput_compressesToAacWithMatchingConfig() = runTest {
        assertRoundTripMatchesConfig(
            resourceName = "sample_mono_24k.ogg",
            config = AudioCompressionConfig(bitrate = 64_000, sampleRate = 24_000, channels = AudioChannels.MONO),
        )
    }

    @Test
    fun aacAdtsInput_compressesToAacWithMatchingConfig() = runTest {
        // AAC-ADTS is an alternative ingest path through Media3 vs AAC-in-MP4. The extractor
        // matrix differs — ADTS frames carry their own sync words instead of piggybacking on
        // the MP4 structure — so a regression in one path wouldn't necessarily break the
        // other. Shipped as a binary resource because Android's public `MediaMuxer` API
        // doesn't expose an ADTS output format, so on-device generation would require manually
        // wrapping each AAC access unit with a 7-byte ADTS header (which is a separate feature
        // that doesn't belong in a test helper).
        assertRoundTripMatchesConfig(
            resourceName = "sample_mono_44k.aac",
            config = AudioCompressionConfig(bitrate = 64_000, sampleRate = 44_100, channels = AudioChannels.MONO),
        )
    }

    @Test
    fun amrNbInput_compressesToAacWithMatchingConfig() = runTest {
        // AMR-NB: 8 kHz mono phone-call codec. Our compressor must be able to transcode it
        // (Media3's AMR extractor + AAC encoder). The output config requests 22.05 kHz / mono
        // since re-upsampling beyond 8 kHz source is the practical "voice messaging" flow.
        val input = File(tempDir, "amr_fixture.3gp")
        AudioInputFixtures.createAmrNb(output = input, durationSeconds = 1)
        assertRoundTripMatchesConfig(
            input = input,
            outputName = "from_amr.m4a",
            config = AudioCompressionConfig(bitrate = 32_000, sampleRate = 22_050, channels = AudioChannels.MONO),
        )
    }

    /**
     * Round-trip gate shared across the multi-format cases. Goes beyond
     * `OutputValidators.isValidM4a` (which only checks container magic bytes) by parsing the
     * output with [android.media.MediaExtractor] and asserting the decoded track matches the
     * requested [AudioCompressionConfig]: AAC mime, correct sample rate + channel count, and a
     * duration within tolerance of the 1 s source. This catches regressions that produce a
     * "valid M4A container with empty / garbage AAC track" — a class of failure our earlier
     * validators couldn't distinguish from success.
     */
    private suspend fun assertRoundTripMatchesConfig(resourceName: String, config: AudioCompressionConfig) =
        assertRoundTripMatchesConfig(
            input = copyResourceToCache(resourceName, tempDir),
            outputName = "from_$resourceName.m4a",
            config = config,
        )

    private suspend fun assertRoundTripMatchesConfig(
        input: File,
        outputName: String,
        config: AudioCompressionConfig,
    ) {
        val output = File(tempDir, outputName)

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config,
        )

        assertTrue(result.isSuccess, "${input.name} → AAC must succeed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        val track = readAudioTrackInfo(output)
        assertEquals("audio/mp4a-latm", track.mime, "Output must be AAC")
        assertEquals(config.sampleRate, track.sampleRate, "Output sample rate must match config")
        assertEquals(config.channels.count, track.channels, "Output channel count must match config")

        val durationMs = readAudioDurationMs(output)
        assertTrue(
            abs(durationMs - SOURCE_DURATION_MS) < DURATION_TOLERANCE_MS,
            "Duration $durationMs ms must be within ${DURATION_TOLERANCE_MS}ms of the ${SOURCE_DURATION_MS}ms source " +
                "— zero / wildly different means the encoder produced an empty or corrupt stream",
        )
    }

    private companion object {
        /** Source fixtures are 1 s 440 Hz sines. */
        const val SOURCE_DURATION_MS = 1_000L

        /** MP3 and Opus each add encoder-delay + frame-padding samples that inflate the
         *  decoded duration by up to ~150 ms on short clips (ffmpeg pads to the next full MP3
         *  frame, and LAME / Opus insert priming samples that the decoder doesn't always
         *  strip). 200 ms is wide enough to absorb that framing slop across all three formats
         *  while still catching the "encoder produced an empty stream" regression (zero-length
         *  output would miss by 1000 ms). */
        const val DURATION_TOLERANCE_MS = 200L
    }
}
