/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end parity tests for CRA-92's new `compress(MediaSource, MediaDestination, ...)`
 * overload. Asserts the "bitwise-identical output" gold standard from the acceptance criteria:
 * the new `FilePath`-dispatch path produces byte-for-byte the same file as the legacy
 * path-based overload, because the former delegates directly to the latter.
 *
 * Also verifies the CompressionProgress contract for audio/video: the new overload emits
 * [CompressionProgress.Phase.COMPRESSING] fractions while the pipeline runs and a single
 * [CompressionProgress.Phase.FINALIZING_OUTPUT] with fraction 1.0f as the last emission on
 * success. On failure, `FINALIZING_OUTPUT` is never emitted — pinned by
 * `audio_newOverload_onFailure_doesNotEmitFinalizing` / the video sibling.
 *
 * Sibling `iosTest/FilePathEndToEndTest.kt` mirrors these assertions on iOS per the
 * KMP parity rule.
 */
class FilePathEndToEndTest {

    private lateinit var tempDir: File
    private val image = AndroidImageCompressor()
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
    fun image_newOverloadProducesBitwiseIdenticalOutput() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacy = File(tempDir, "legacy.jpg")
        val novel = File(tempDir, "novel.jpg")

        val legacyResult = image.compress(input.absolutePath, legacy.absolutePath, ImageCompressionConfig())
        val novelResult = image.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(novel.absolutePath),
            config = ImageCompressionConfig(),
        )

        legacyResult.isSuccess shouldBe true
        novelResult.isSuccess shouldBe true
        novel.sha256() shouldBe legacy.sha256()
    }

    @Test
    fun audio_newOverloadProducesBitwiseIdenticalOutput() = runTest {
        val input = createTestWav(durationSeconds = AUDIO_DURATION_S)
        val legacy = File(tempDir, "legacy.m4a")
        val novel = File(tempDir, "novel.m4a")

        val legacyResult = audio.compress(input.absolutePath, legacy.absolutePath, AudioCompressionConfig())
        val novelResult = audio.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(novel.absolutePath),
            config = AudioCompressionConfig(),
        )

        legacyResult.isSuccess shouldBe true
        novelResult.isSuccess shouldBe true
        novel.sha256() shouldBe legacy.sha256()
    }

    @Test
    fun video_newOverloadProducesBitwiseIdenticalOutput() = runTest {
        val input = File(tempDir, "input.mp4").also {
            Mp4Generator.generateMp4(it, frameCount = VIDEO_FRAME_COUNT)
        }
        val legacy = File(tempDir, "legacy.mp4")
        val novel = File(tempDir, "novel.mp4")

        val legacyResult = video.compress(input.absolutePath, legacy.absolutePath, VideoCompressionConfig())
        val novelResult = video.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.FilePath(novel.absolutePath),
            config = VideoCompressionConfig(),
        )

        legacyResult.isSuccess shouldBe true
        novelResult.isSuccess shouldBe true
        novel.sha256() shouldBe legacy.sha256()
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

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val IMAGE_SIDE = 512
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}
