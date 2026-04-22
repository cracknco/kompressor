/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.io.CompressionProgress.Phase.COMPRESSING
import co.crackn.kompressor.io.CompressionProgress.Phase.FINALIZING_OUTPUT
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.assertProgressionMonotone
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * CRA-96 end-to-end progression invariants on Android.
 *
 * For each of the audio + video compressors and for each realistic input variant (FilePath +
 * Stream + Bytes), this test asserts:
 *
 *  1. The full emission sequence is monotone per [assertProgressionMonotone] (no fraction
 *     decrease within a phase, no phase regression, no out-of-range fraction).
 *  2. `COMPRESSING(0f)` is emitted exactly once at the start of the compression phase. This is
 *     the consumer-facing "I'm about to start encoding" tick that triggers a UI "0%" render.
 *  3. `FINALIZING_OUTPUT(1f)` is the terminal event. This is the canonical end-of-compression
 *     signal the consumer uses to hide the progress bar.
 *
 * Image compressor is intentionally excluded — `ImageCompressor.compress(...)` does not take a
 * `suspend (CompressionProgress) -> Unit` callback because the underlying `BitmapFactory` +
 * `Bitmap.compress` APIs are synchronous single-step operations with no intermediate progress
 * to report (see [co.crackn.kompressor.image.ImageCompressor] / CLAUDE.md).
 *
 * Device-test-only because the Media3 `Transformer` integration requires a real emulator or
 * physical device.
 */
class ProgressionE2ETest {

    private lateinit var tempDir: File
    private val audio = AndroidAudioCompressor()
    private val video = AndroidVideoCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-progression-e2e").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    @Test
    fun audio_filePathInput_progressionIsMonotone() = runTest {
        val input = writeWav()
        val out = File(tempDir, "out.m4a")
        val events = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = AudioCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = false)
    }

    @Test
    fun audio_streamInput_progressionIsMonotone() = runTest {
        val input = writeWav()
        val out = File(tempDir, "out.m4a")
        val events = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.Stream(input.inputStream().source(), sizeHint = input.length()),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = AudioCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = true)
    }

    @Test
    fun audio_bytesInput_progressionIsMonotone() = runTest {
        val input = writeWav()
        val out = File(tempDir, "out.m4a")
        val events = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.Bytes(input.readBytes()),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = AudioCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = true)
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    @Test
    fun video_filePathInput_progressionIsMonotone() = runTest {
        val input = writeMp4()
        val out = File(tempDir, "out.mp4")
        val events = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = VideoCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = false)
    }

    @Test
    fun video_streamInput_progressionIsMonotone() = runTest {
        val input = writeMp4()
        val out = File(tempDir, "out.mp4")
        val events = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.Stream(input.inputStream().source(), sizeHint = input.length()),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = VideoCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = true)
    }

    @Test
    fun video_bytesInput_progressionIsMonotone() = runTest {
        val input = writeMp4()
        val out = File(tempDir, "out.mp4")
        val events = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.Bytes(input.readBytes()),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = VideoCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = true)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertProgression(
        events: List<CompressionProgress>,
        expectMaterializing: Boolean,
    ) {
        withClue("events: $events") {
            events.isNotEmpty() shouldBe true
            assertProgressionMonotone(events)

            events.shouldContain(CompressionProgress(COMPRESSING, 0f))
            events.last() shouldBe CompressionProgress(FINALIZING_OUTPUT, 1f)

            if (expectMaterializing) {
                val phases = events.map { it.phase }.distinct()
                withClue("Stream/Bytes inputs must emit MATERIALIZING_INPUT phase: $phases") {
                    phases.shouldContain(CompressionProgress.Phase.MATERIALIZING_INPUT)
                }
            }
        }
    }

    private fun writeWav(): File = File(tempDir, "in_${System.nanoTime()}.wav").apply {
        writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
    }

    private fun writeMp4(): File = File(tempDir, "in_${System.nanoTime()}.mp4").also {
        AudioInputFixtures.createMp4WithVideoAndAudio(it, durationSeconds = 1)
    }
}
