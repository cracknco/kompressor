/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.io.CompressionProgress.Phase.COMPRESSING
import co.crackn.kompressor.io.CompressionProgress.Phase.FINALIZING_OUTPUT
import co.crackn.kompressor.io.CompressionProgress.Phase.MATERIALIZING_INPUT
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.assertProgressionMonotone
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okio.Buffer
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * iOS sibling of `androidDeviceTest/ProgressionE2ETest` for CRA-96.
 *
 * For each of `IosAudioCompressor` + `IosVideoCompressor` and for each realistic input variant
 * (FilePath + Stream + Bytes), this test asserts:
 *
 *  1. The full emission sequence is monotone per [assertProgressionMonotone].
 *  2. `COMPRESSING(0f)` is emitted exactly once at the start of the compression phase.
 *  3. `FINALIZING_OUTPUT(1f)` is the terminal event.
 *  4. `MATERIALIZING_INPUT` is emitted for Stream + Bytes inputs (they materialize to a temp
 *     file before AVFoundation can consume them) and absent for FilePath inputs.
 *
 * Image compressor is intentionally excluded — `ImageCompressor.compress(...)` has no
 * `suspend (CompressionProgress) -> Unit` overload (see `IosImageCompressor` / CLAUDE.md).
 */
class ProgressionE2ETest {

    private lateinit var testDir: String
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-progression-e2e-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    @Test
    fun audio_filePathInput_progressionIsMonotone() = runTest {
        val inputPath = createTestWav()
        val output = testDir + "out.m4a"
        val events = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(),
        ) { events += it }

        result.isSuccess shouldBe true
        assertProgression(events, expectMaterializing = false)
    }

    @Test
    fun audio_streamInput_progressionIsMonotone() = runTest {
        val inputPath = createTestWav()
        val bytes = readBytes(inputPath)
        val output = testDir + "out.m4a"
        val events = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.Stream(
                Buffer().apply { write(bytes) },
                sizeHint = bytes.size.toLong(),
            ),
            output = MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(),
        ) { events += it }

        result.isSuccess shouldBe true
        assertProgression(events, expectMaterializing = true)
    }

    @Test
    fun audio_bytesInput_progressionIsMonotone() = runTest {
        val inputPath = createTestWav()
        val bytes = readBytes(inputPath)
        val output = testDir + "out.m4a"
        val events = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.Bytes(bytes),
            output = MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(),
        ) { events += it }

        result.isSuccess shouldBe true
        assertProgression(events, expectMaterializing = true)
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    @Test
    fun video_filePathInput_progressionIsMonotone() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "in.mp4", frameCount = VIDEO_FRAME_COUNT)
        val output = testDir + "out.mp4"
        val events = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(output),
            config = VideoCompressionConfig(),
        ) { events += it }

        result.isSuccess shouldBe true
        assertProgression(events, expectMaterializing = false)
    }

    @Test
    fun video_streamInput_progressionIsMonotone() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "in.mp4", frameCount = VIDEO_FRAME_COUNT)
        val bytes = readBytes(inputPath)
        val output = testDir + "out.mp4"
        val events = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.Stream(
                Buffer().apply { write(bytes) },
                sizeHint = bytes.size.toLong(),
            ),
            output = MediaDestination.Local.FilePath(output),
            config = VideoCompressionConfig(),
        ) { events += it }

        result.isSuccess shouldBe true
        assertProgression(events, expectMaterializing = true)
    }

    @Test
    fun video_bytesInput_progressionIsMonotone() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "in.mp4", frameCount = VIDEO_FRAME_COUNT)
        val bytes = readBytes(inputPath)
        val output = testDir + "out.mp4"
        val events = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.Bytes(bytes),
            output = MediaDestination.Local.FilePath(output),
            config = VideoCompressionConfig(),
        ) { events += it }

        result.isSuccess shouldBe true
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

            val phases = events.map { it.phase }.distinct()
            if (expectMaterializing) {
                withClue("Stream/Bytes inputs must emit MATERIALIZING_INPUT phase: $phases") {
                    phases.shouldContain(MATERIALIZING_INPUT)
                }
            }
        }
    }

    private fun createTestWav(): String {
        val bytes = WavGenerator.generateWavBytes(AUDIO_DURATION_S, WAV_SAMPLE_RATE, WAV_CHANNELS)
        val path = testDir + "in.wav"
        writeBytes(path, bytes)
        return path
    }

    private companion object {
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
    }
}
