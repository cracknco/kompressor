/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * End-to-end progression-contract tests for the `compress(MediaSource, MediaDestination, ...)`
 * entry point on the iOS `FilePath` fast path. Sibling of `androidDeviceTest/FilePathEndToEndTest`.
 * Exercises the `CompressionProgress` invariants: audio/video emit `COMPRESSING` fractions plus
 * a terminal `FINALIZING_OUTPUT(1f)` on success; on failure, `FINALIZING_OUTPUT` is never
 * emitted (pinned by `audio_newOverload_onFailure_doesNotEmitFinalizing` / video sibling).
 *
 * CRA-97 note: the earlier `*_producesBitwiseIdenticalOutput` tests comparing the (now-
 * removed) path-based overload to the `MediaSource` overload were retired — with the legacy
 * overload gone both sides called the same code path, so the comparison was tautological.
 */
class FilePathEndToEndTest {

    private lateinit var testDir: String
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-filepath-e2e-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun audio_newOverloadEmitsCompressingThenFinalizing() = runTest {
        val inputPath = createTestWav(AUDIO_DURATION_S)
        val outputPath = testDir + "output.m4a"
        val emissions = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(outputPath),
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
        val inputPath = Mp4Generator.generateMp4(testDir + "input.mp4", frameCount = VIDEO_FRAME_COUNT)
        val outputPath = testDir + "output.mp4"
        val emissions = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(outputPath),
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
    // the failure before the terminal FINALIZING_OUTPUT emit.

    @Test
    fun audio_newOverload_onFailure_doesNotEmitFinalizing() = runTest {
        val missingInput = testDir + "does-not-exist.wav"
        val outputPath = testDir + "output.m4a"
        val emissions = mutableListOf<CompressionProgress>()

        val result = audio.compress(
            input = MediaSource.Local.FilePath(missingInput),
            output = MediaDestination.Local.FilePath(outputPath),
            config = AudioCompressionConfig(),
            onProgress = { emissions.add(it) },
        )

        result.isFailure shouldBe true
        emissions.any { it.phase == CompressionProgress.Phase.FINALIZING_OUTPUT } shouldBe false
    }

    @Test
    fun video_newOverload_onFailure_doesNotEmitFinalizing() = runTest {
        val missingInput = testDir + "does-not-exist.mp4"
        val outputPath = testDir + "output.mp4"
        val emissions = mutableListOf<CompressionProgress>()

        val result = video.compress(
            input = MediaSource.Local.FilePath(missingInput),
            output = MediaDestination.Local.FilePath(outputPath),
            config = VideoCompressionConfig(),
            onProgress = { emissions.add(it) },
        )

        result.isFailure shouldBe true
        emissions.any { it.phase == CompressionProgress.Phase.FINALIZING_OUTPUT } shouldBe false
    }

    private fun createTestWav(durationSeconds: Int): String {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, WAV_SAMPLE_RATE, WAV_CHANNELS)
        val path = testDir + "input.wav"
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
