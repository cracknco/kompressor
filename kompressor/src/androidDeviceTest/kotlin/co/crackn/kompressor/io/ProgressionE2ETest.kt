/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.io.CompressionProgress.Phase.COMPRESSING
import co.crackn.kompressor.io.CompressionProgress.Phase.FINALIZING_OUTPUT
import co.crackn.kompressor.io.CompressionProgress.Phase.MATERIALIZING_INPUT
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

    // ── PFD (CRA-99) ──────────────────────────────────────────────────────────
    //
    // PFD inputs used to emit zero MATERIALIZING_INPUT events because the legacy
    // materializePfdHandle did a raw `FileInputStream.copyTo` — no chunk boundaries were
    // observable. CRA-99 routes the copy through the shared TempFileMaterializer + probe-seeded
    // sizeHint, so large PFDs now emit monotone fraction-accurate ticks just like the
    // Stream/Bytes paths. Tests assert the full MATERIALIZING_INPUT → COMPRESSING → FINALIZING
    // sequence AND that more than one MATERIALIZING_INPUT fraction is observed — the
    // [assertPfdMaterializationTicks] helper asserts on the observable invariant (≥ 2 ticks +
    // ≥ 1 mid-copy fraction) rather than coupling to `TempFileMaterializer.BUFFER_SIZE`.

    @Test
    fun audio_pfdInput_progressionIsMonotone() = runTest {
        val input = writeWav()
        val out = File(tempDir, "out.m4a")
        val events = mutableListOf<CompressionProgress>()
        val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)

        val result = audio.compress(
            input = MediaSource.of(pfd),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = AudioCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = true)
        assertPfdMaterializationTicks(events)
    }

    @Test
    fun video_pfdInput_progressionIsMonotone() = runTest {
        val input = writeMp4()
        val out = File(tempDir, "out.mp4")
        val events = mutableListOf<CompressionProgress>()
        val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)

        val result = video.compress(
            input = MediaSource.of(pfd),
            output = MediaDestination.Local.FilePath(out.absolutePath),
            config = VideoCompressionConfig(),
        ) { events += it }

        withClue("compress: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        assertProgression(events, expectMaterializing = true)
        assertPfdMaterializationTicks(events)
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

    /**
     * CRA-99 invariant for PFD inputs — asserts the probe-seeded `materializePfdHandle` emits
     * **more than one** `MATERIALIZING_INPUT` tick and that **at least one** fraction is a
     * real mid-copy value strictly in `(0f, 1f)`.
     *
     * Both invariants rely only on the fixture being larger than any reasonable
     * [TempFileMaterializer] buffer — the WAV / MP4 fixtures used by these tests are hard-coded
     * well above 100 KB (~180 KB 1s-stereo-44.1kHz WAV, and the MP4 fixture is larger still).
     * Asserting against the observable output rather than the internal `BUFFER_SIZE` constant
     * keeps these tests robust to materialiser tuning (e.g. if the buffer is raised to 128 KB
     * for throughput, these tests still pass unchanged).
     *
     * A raw `copyTo` without probe wiring would either emit zero `MATERIALIZING_INPUT` ticks
     * (old behaviour, caught by the `≥ 2` check) or emit every tick at `0f` / `1f` only (the
     * degraded "probe returned null → flat-0 heartbeat" path, caught by the mid-copy check).
     */
    private fun assertPfdMaterializationTicks(events: List<CompressionProgress>) {
        val materializingFractions = events.filter { it.phase == MATERIALIZING_INPUT }.map { it.fraction }
        withClue("materializingFractions=$materializingFractions") {
            (materializingFractions.size >= 2) shouldBe true
            // At least one fraction is a real mid-copy value strictly in (0, 1) — rules out
            // BOTH the degraded "probe returned null → flat-0 heartbeat" path (every tick 0f)
            // AND the hypothetical "only terminal tick" failure mode (every tick 1f at EOF).
            materializingFractions.any { it > 0f && it < 1f } shouldBe true
        }
    }

    private fun writeWav(): File = File(tempDir, "in_${System.nanoTime()}.wav").apply {
        writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
    }

    private fun writeMp4(): File = File(tempDir, "in_${System.nanoTime()}.mp4").also {
        AudioInputFixtures.createMp4WithVideoAndAudio(it, durationSeconds = 1)
    }
}
