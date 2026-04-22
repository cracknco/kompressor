/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readBytes
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
 * End-to-end parity tests for CRA-92's new `compress(MediaSource, MediaDestination, ...)`
 * overload on iOS. Sibling of the androidDeviceTest `FilePathEndToEndTest`: asserts the
 * same "bitwise-identical output" gold-standard contract (the new entry delegates to the
 * path-based overload, so the outputs must match byte-for-byte) and the same progress
 * contract (COMPRESSING fractions followed by a terminal FINALIZING_OUTPUT(1f), and
 * FINALIZING_OUTPUT absent on failure).
 *
 * Parity-required per the KMP rule in `cyrus-skills:implementation` — every new platform
 * behaviour lands on both Android and iOS in the same PR.
 */
class FilePathEndToEndTest {

    private lateinit var testDir: String
    private val image = IosImageCompressor()
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
    fun image_newOverloadProducesBitwiseIdenticalOutput() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyPath = testDir + "legacy.jpg"
        val novelPath = testDir + "novel.jpg"

        val legacy = image.compress(inputPath, legacyPath, ImageCompressionConfig())
        val novel = image.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(novelPath),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun audio_newOverloadProducesBitwiseIdenticalOutput() = runTest {
        val inputPath = createTestWav(AUDIO_DURATION_S)
        val legacyPath = testDir + "legacy.m4a"
        val novelPath = testDir + "novel.m4a"

        val legacy = audio.compress(inputPath, legacyPath, AudioCompressionConfig())
        val novel = audio.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(novelPath),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun video_newOverloadProducesBitwiseIdenticalOutput() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "input.mp4", frameCount = VIDEO_FRAME_COUNT)
        val legacyPath = testDir + "legacy.mp4"
        val novelPath = testDir + "novel.mp4"

        val legacy = video.compress(inputPath, legacyPath, VideoCompressionConfig())
        val novel = video.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.FilePath(novelPath),
            config = VideoCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
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
        const val IMAGE_SIDE = 512
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
    }
}
