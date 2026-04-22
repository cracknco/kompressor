/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.golden

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.readTopLevelMp4Boxes
import co.crackn.kompressor.testutil.TestConstants.DURATION_TOLERANCE_MS
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readAudioDurationMs
import co.crackn.kompressor.testutil.readAudioMetadata
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Golden tests verify that compression of known inputs produces outputs within
 * expected functional criteria. These catch regressions in encoder behavior,
 * file size expectations, and metadata correctness.
 */
class GoldenAudioTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-golden-audio").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun stereo44k_defaultConfig_producesExpectedOutput() = runTest {
        val input = createWav(durationSeconds = 2, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_default.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()

        assertTrue(output.exists(), "Output file must exist")
        assertTrue(compression.outputSize > 0, "Output must be non-empty")
        assertTrue(
            compression.outputSize < compression.inputSize,
            "AAC should compress PCM WAV: output=${compression.outputSize}, input=${compression.inputSize}",
        )
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        val outputDurationMs = readAudioDurationMs(output)
        assertTrue(
            abs(outputDurationMs - EXPECTED_DURATION_2S_MS) < DURATION_TOLERANCE_MS,
            "Duration $outputDurationMs ms should be within ${DURATION_TOLERANCE_MS}ms of ${EXPECTED_DURATION_2S_MS}ms",
        )

        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Sample rate should match default config")
        assertEquals(STEREO, metadata.channels, "Channel count should match default config")

        // Structural gate: verify the tight MP4 muxer factory is actually in effect. Media3
        // 1.10's default muxer front-loads a `moov` reservation of 400_000 bytes; without the
        // `setFreeSpaceAfterFileTypeBoxBytes(1)` override every output contains a `free`
        // padding box of that size, turning "size ≈ f(bitrate)" assertions into noise. If a
        // future refactor drops the muxer factory wiring, this assertion catches it directly
        // at the container level — without depending on byte-count ranges that might still
        // pass by coincidence.
        val freeBoxTotal = readTopLevelMp4Boxes(output).filter { it.type == "free" }.sumOf { it.size }
        assertTrue(
            freeBoxTotal < MAX_FREE_BOX_BYTES,
            "Top-level `free` boxes total $freeBoxTotal B — the tight-muxer override must keep " +
                "this below $MAX_FREE_BOX_BYTES B (Media3 default reserves 400 000 B)",
        )
    }

    @Test
    fun stereo44k_voicePreset_producesExpectedOutput() = runTest {
        val input = createWav(durationSeconds = 2, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_voice.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            AudioPresets.VOICE_MESSAGE,
        )

        assertTrue(result.isSuccess)
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        val metadata = readAudioMetadata(output)
        assertEquals(AudioPresets.VOICE_MESSAGE.sampleRate, metadata.sampleRate, "Voice preset: 22.05kHz")
        assertEquals(AudioChannels.MONO.count, metadata.channels, "Voice preset: mono")

        // Voice preset at 32kbps should be significantly smaller than default 128kbps
        val defaultOutput = File(tempDir, "golden_default_compare.m4a")
        compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(defaultOutput.absolutePath),
        )
        assertTrue(
            output.length() < defaultOutput.length(),
            "Voice preset should produce smaller file than default",
        )
    }

    @Test
    fun outputSizeWithinExpectedRange() = runTest {
        val input = createWav(durationSeconds = 3, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_size_check.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            AudioCompressionConfig(bitrate = 128_000),
        )

        assertTrue(result.isSuccess)
        assertTrue(
            output.length() in EXPECTED_MIN_BYTES..EXPECTED_MAX_BYTES,
            "3s at 128kbps: expected $EXPECTED_MIN_BYTES-$EXPECTED_MAX_BYTES bytes, got ${output.length()}",
        )
    }

    @Test
    fun aacInput_matchingConfig_triggersFastPath() = runTest {
        val input = File(tempDir, "golden_aac_in.m4a")
        AudioInputFixtures.createAacM4a(
            input,
            durationSeconds = 2,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
            bitrate = 128_000,
        )
        val passthroughOutput = File(tempDir, "golden_aac_passthrough.m4a")
        val transcodeOutput = File(tempDir, "golden_aac_transcoded.m4a")

        // Record a full re-encode wall time as the baseline — this is the same code path the
        // fast path must avoid. Using a relative ratio instead of an absolute budget (we used
        // to assert `< 3 s`, which was flaky on loaded CI hardware) isolates the behaviour
        // from the device's encoder throughput: regardless of absolute speed, passthrough
        // must be strictly faster than re-encoding because it skips decode + encode entirely.
        val transcodeResult = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(transcodeOutput.absolutePath),
            AudioCompressionConfig(bitrate = 64_000),
        )
        assertTrue(transcodeResult.isSuccess, "Re-encode baseline must succeed: ${transcodeResult.exceptionOrNull()}")
        val transcodeMs = transcodeResult.getOrThrow().durationMs

        val passthroughResult = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(passthroughOutput.absolutePath),
        )

        assertTrue(
            passthroughResult.isSuccess,
            "Passthrough should succeed: ${passthroughResult.exceptionOrNull()}",
        )
        assertTrue(OutputValidators.isValidM4a(passthroughOutput.readBytes()))

        // Functional invariants: output size within a tight tolerance of input (remux only,
        // no re-encode) — this is the signature that Media3 actually took the fast path.
        val sizeRatio = passthroughOutput.length().toDouble() / input.length().toDouble()
        assertTrue(
            sizeRatio in FAST_PATH_SIZE_RATIO_MIN..FAST_PATH_SIZE_RATIO_MAX,
            "Passthrough size ratio $sizeRatio should be near 1.0",
        )

        // Performance invariant: the fast path must be *substantially* faster than a full
        // re-encode of the same source. The relative budget is deliberately loose (33 %) —
        // we only need the check to catch a regression where the fast path is silently
        // disabled and Media3 ends up re-encoding, which would push durations into the
        // same order of magnitude as the baseline.
        val passthroughMs = passthroughResult.getOrThrow().durationMs
        assertTrue(
            passthroughMs < transcodeMs * FAST_PATH_RELATIVE_BUDGET,
            "Passthrough ($passthroughMs ms) should be < ${FAST_PATH_RELATIVE_BUDGET * 100}% of " +
                "re-encode baseline ($transcodeMs ms)",
        )
    }

    @Test
    fun outputBitrateLowerThanInputForVoicePreset() = runTest {
        val input = createWav(durationSeconds = 3, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_voice_vs_input.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            AudioPresets.VOICE_MESSAGE,
        )
        assertTrue(result.isSuccess)

        // Voice preset is 32kbps mono 22kHz — vastly smaller than 44.1kHz stereo PCM input.
        assertTrue(
            output.length() < input.length() / 4,
            "Voice preset output ${output.length()} should be <1/4 of PCM input ${input.length()}",
        )
    }

    @Test
    fun outputDurationMatchesInputAcrossRateConversion() = runTest {
        val durationSec = 3
        val input = createWav(durationSeconds = durationSec, sampleRate = SAMPLE_RATE_44K, channels = STEREO)
        val output = File(tempDir, "golden_duration_preservation.m4a")

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            AudioCompressionConfig(sampleRate = 22_050, channels = AudioChannels.MONO),
        )
        assertTrue(result.isSuccess)

        val outMs = readAudioDurationMs(output)
        val expectedMs = durationSec * 1_000L
        assertTrue(
            abs(outMs - expectedMs) < DURATION_TOLERANCE_MS,
            "Duration $outMs ms should be within ${DURATION_TOLERANCE_MS}ms of $expectedMs ms",
        )
    }

    private fun createWav(durationSeconds: Int, sampleRate: Int, channels: Int): File {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val file = File(tempDir, "golden_input_${sampleRate}_${channels}ch_${durationSeconds}s.wav")
        file.writeBytes(bytes)
        return file
    }

    private companion object {
        const val EXPECTED_DURATION_2S_MS = 2_000L
        const val EXPECTED_MIN_BYTES = 24_000L // 3s at 128kbps ≈ 48KB, -50% margin
        const val EXPECTED_MAX_BYTES = 72_000L // 3s at 128kbps ≈ 48KB, +50% margin

        // Passthrough size expectations.
        const val FAST_PATH_SIZE_RATIO_MIN = 0.90
        const val FAST_PATH_SIZE_RATIO_MAX = 1.10
        const val FAST_PATH_RELATIVE_BUDGET = 0.33

        // Tight-muxer override keeps `free` padding at ~9 B per box (the minimum header +
        // 1-byte payload we reserve via `setFreeSpaceAfterFileTypeBoxBytes(1)`). 128 B cap is
        // strict enough to catch any meaningful padding regression (including partial defaults
        // around a few KB) while leaving room for ~14 tiny free boxes — far more than Media3
        // should ever emit on a well-formed export.
        const val MAX_FREE_BOX_BYTES = 128L
    }
}
