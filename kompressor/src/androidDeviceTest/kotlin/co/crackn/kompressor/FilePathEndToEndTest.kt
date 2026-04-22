/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end progression-contract tests for the `compress(MediaSource, MediaDestination, ...)`
 * entry point on the `FilePath` fast path. Exercises the `CompressionProgress` invariants:
 * audio/video emit [CompressionProgress.Phase.COMPRESSING] fractions while the pipeline runs
 * plus a terminal [CompressionProgress.Phase.FINALIZING_OUTPUT]`(1f)` on success; on failure
 * `FINALIZING_OUTPUT` is never emitted — pinned by
 * `audio_newOverload_onFailure_doesNotEmitFinalizing` / the video sibling.
 *
 * CRA-97 note: the earlier `*_producesBitwiseIdenticalOutput` tests that compared the (now-
 * removed) path-based overload to the `MediaSource` overload were retired — with the legacy
 * overload gone the two sides called the same code path, so the comparison was tautological.
 * Bitwise parity vs non-FilePath inputs (Stream / Bytes) lives in `StreamAndBytesEndToEndTest`.
 *
 * Sibling `iosTest/FilePathEndToEndTest.kt` mirrors these assertions on iOS per the
 * KMP parity rule.
 */
class FilePathEndToEndTest {

    private lateinit var tempDir: File
    private val audio = AndroidAudioCompressor()
    private val video = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-filepath-e2e").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun audio_newOverloadEmitsCompressingThenFinalizing() = runTest {
        val input = createTestWav(durationSeconds = AUDIO_DURATION_S)
        val output = File(tempDir, "output.m4a")
        val emissions = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(),
            onProgress = { emissions.add(it) },
        )

        result.isSuccess shouldBe true
        emissions.shouldNotBeEmpty()
        emissions.any { it.phase == CompressionProgress.Phase.MATERIALIZING_INPUT } shouldBe false
        val last = emissions.last()
        last.phase shouldBe CompressionProgress.Phase.FINALIZING_OUTPUT
        last.fraction shouldBe 1f
    }

    @Test
    fun video_newOverloadEmitsCompressingThenFinalizing() = runTest {
        val input = File(tempDir, "input.mp4").also {
            Mp4Generator.generateMp4(it, frameCount = VIDEO_FRAME_COUNT)
        }
        val output = File(tempDir, "output.mp4")
        val emissions = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = VideoCompressionConfig(),
            onProgress = { emissions.add(it) },
        )

        result.isSuccess shouldBe true
        emissions.shouldNotBeEmpty()
        emissions.any { it.phase == CompressionProgress.Phase.MATERIALIZING_INPUT } shouldBe false
        val last = emissions.last()
        last.phase shouldBe CompressionProgress.Phase.FINALIZING_OUTPUT
        last.fraction shouldBe 1f
    }

    // Pins the "FINALIZING_OUTPUT is emitted only on success" contract documented on
    // AudioCompressor.compress / VideoCompressor.compress. If the inner path-based pipeline
    // throws (here via a non-existent input), .getOrThrow() in the outer overload propagates
    // the failure before the terminal FINALIZING_OUTPUT emit — so no consumer UI ever sees a
    // FINALIZING_OUTPUT(1f) for a failed compression. A regression that moved the emit
    // outside suspendRunCatching (or before .getOrThrow()) would trip these two tests.

    @Test
    fun audio_newOverload_onFailure_doesNotEmitFinalizing() = runTest {
        val missingInput = File(tempDir, "does-not-exist.wav")
        val output = File(tempDir, "output.m4a")
        val emissions = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.FilePath(missingInput.absolutePath),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(),
            onProgress = { emissions.add(it) },
        )

        result.isFailure shouldBe true
        emissions.any { it.phase == CompressionProgress.Phase.FINALIZING_OUTPUT } shouldBe false
    }

    @Test
    fun video_newOverload_onFailure_doesNotEmitFinalizing() = runTest {
        val missingInput = File(tempDir, "does-not-exist.mp4")
        val output = File(tempDir, "output.mp4")
        val emissions = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.FilePath(missingInput.absolutePath),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = VideoCompressionConfig(),
            onProgress = { emissions.add(it) },
        )

        result.isFailure shouldBe true
        emissions.any { it.phase == CompressionProgress.Phase.FINALIZING_OUTPUT } shouldBe false
    }

    private fun createTestWav(durationSeconds: Int): File {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, WAV_SAMPLE_RATE, WAV_CHANNELS)
        val file = File(tempDir, "input.wav")
        file.writeBytes(bytes)
        return file
    }

    private companion object {
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
    }
}
