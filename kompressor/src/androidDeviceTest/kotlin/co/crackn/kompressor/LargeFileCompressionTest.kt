/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readAudioDurationMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Large-input regression coverage for image and audio compressors. Catches OOMs and degenerate
 * progress / streaming behaviour that don't surface on the small fixtures used in the rest of
 * the device-test suite.
 *
 * **Scope note**: video is intentionally excluded — Firebase Test Lab runs are time-capped and
 * a 30-s 1080p transcode on a slow emulator can take 60 s+ on its own. Video performance is
 * already validated by the golden + property tests; this file focuses on the dimensions where
 * a generous input is the load-bearing variable (decoded bitmap memory for image, streaming
 * + duration accounting for audio).
 */
class LargeFileCompressionTest {

    private lateinit var tempDir: File
    private val imageCompressor = AndroidImageCompressor()
    private val audioCompressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-large-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun image_largeFourThousandByThreeThousand_compressesToValidJpeg() = runTest {
        val input = createTestImage(tempDir, LARGE_IMAGE_WIDTH, LARGE_IMAGE_HEIGHT)
        val output = File(tempDir, "large.jpg")

        val result = imageCompressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "Large image compression failed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(output.exists(), "Output file missing")
        assertTrue(output.length() > 0, "Output file is empty")
        assertTrue(compression.outputSize > 0, "outputSize must be positive")
        assertTrue(
            compression.outputSize < compression.inputSize,
            "Large continuous-tone PNG (${compression.inputSize} B) should compress to smaller " +
                "JPEG (${compression.outputSize} B)",
        )
        assertTrue(
            OutputValidators.isValidJpeg(output.readBytes()),
            "Output should be valid JPEG",
        )
    }

    @Test
    fun audio_thirtySecondWav_compressesAndDurationPreserved() = runTest {
        val input = File(tempDir, "long.wav").apply {
            writeBytes(
                WavGenerator.generateWavBytes(
                    durationSeconds = LARGE_AUDIO_DURATION_SECONDS,
                    sampleRate = AUDIO_SAMPLE_RATE,
                    channels = AUDIO_CHANNELS,
                ),
            )
        }
        val output = File(tempDir, "long.m4a")

        val result = audioCompressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "Large audio compression failed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(output.exists(), "Output file missing")
        assertTrue(output.length() > 0, "Output file is empty")
        assertTrue(compression.outputSize > 0, "outputSize must be positive")
        assertTrue(
            OutputValidators.isValidM4a(output.readBytes()),
            "Output should be valid M4A",
        )

        val outputDurationMs = readAudioDurationMs(output)
        val expectedMs = LARGE_AUDIO_DURATION_SECONDS * MS_PER_SECOND
        val drift = abs(outputDurationMs - expectedMs)
        assertTrue(
            drift <= DURATION_TOLERANCE_MS,
            "Output duration ${outputDurationMs}ms drifts >${DURATION_TOLERANCE_MS}ms from " +
                "input ${expectedMs}ms (drift=${drift}ms)",
        )
    }

    private companion object {
        // 4000x3000 ≈ 12 MP — a typical phone-camera capture. Decoded ARGB_8888 footprint is
        // ~48 MB which exercises Bitmap allocator pressure without OOM-ing the host.
        const val LARGE_IMAGE_WIDTH = 4000
        const val LARGE_IMAGE_HEIGHT = 3000

        // 30 s WAV at 44.1 kHz stereo PCM16 = ~5 MB raw. AAC at 128 kbps lands around 480 KB.
        const val LARGE_AUDIO_DURATION_SECONDS = 30
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_CHANNELS = 2

        const val MS_PER_SECOND = 1_000L

        // AAC encoder priming/lookahead introduces a small duration delta around the first
        // frame; 250 ms is the same tolerance used by the rest of the audio device suite.
        const val DURATION_TOLERANCE_MS = 250L
    }
}
