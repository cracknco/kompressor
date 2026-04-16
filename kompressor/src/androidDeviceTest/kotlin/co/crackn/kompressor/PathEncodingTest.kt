/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.MinimalPngFixtures
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.video.AndroidVideoCompressor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Verifies that each compressor round-trips through file paths with spaces, accented characters,
 * CJK characters, and emoji. Every path exercises both the input side (reading the source) and
 * the output side (writing the output) so a platform regression in either direction is caught.
 */
class PathEncodingTest {

    private lateinit var tempDir: File
    private val image = AndroidImageCompressor()
    private val audio = AndroidAudioCompressor()
    private val video = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-path-encoding-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun imageCompress_pathWithSpaces_succeeds() = runTest {
        runImageAt("sub dir", "in put.png", "out put.jpg")
    }

    @Test
    fun imageCompress_pathWithAccentedChars_succeeds() = runTest {
        runImageAt("café", "résumé.png", "sortie.jpg")
    }

    @Test
    fun imageCompress_pathWithCjkChars_succeeds() = runTest {
        runImageAt("测试", "文件.png", "输出.jpg")
    }

    @Test
    fun imageCompress_pathWithEmoji_succeeds() = runTest {
        runImageAt("\uD83D\uDCF7dir", "\uD83D\uDCF8photo.png", "\uD83D\uDCF7out.jpg")
    }

    @Test
    fun audioCompress_pathWithSpaces_succeeds() = runTest {
        runAudioAt("sub dir", "in put.m4a", "out put.m4a")
    }

    @Test
    fun audioCompress_pathWithAccentedChars_succeeds() = runTest {
        runAudioAt("café", "résumé.m4a", "sortie.m4a")
    }

    @Test
    fun audioCompress_pathWithCjkChars_succeeds() = runTest {
        runAudioAt("测试", "文件.m4a", "输出.m4a")
    }

    @Test
    fun audioCompress_pathWithEmoji_succeeds() = runTest {
        runAudioAt("\uD83C\uDFB5dir", "\uD83C\uDFB6song.m4a", "\uD83C\uDFB5out.m4a")
    }

    @Test
    fun videoCompress_pathWithSpaces_succeeds() = runTest {
        runVideoAt("sub dir", "in put.mp4", "out put.mp4")
    }

    @Test
    fun videoCompress_pathWithAccentedChars_succeeds() = runTest {
        runVideoAt("café", "résumé.mp4", "sortie.mp4")
    }

    @Test
    fun videoCompress_pathWithCjkChars_succeeds() = runTest {
        runVideoAt("测试", "视频.mp4", "输出.mp4")
    }

    @Test
    fun videoCompress_pathWithEmoji_succeeds() = runTest {
        runVideoAt("\uD83C\uDFAAdir", "\uD83C\uDFACclip.mp4", "\uD83C\uDFAAout.mp4")
    }

    private suspend fun runImageAt(subdir: String, inputName: String, outputName: String) {
        val inDir = File(tempDir, subdir).apply { mkdirs() }
        val outDir = File(tempDir, "$subdir-out").apply { mkdirs() }
        val input = File(inDir, inputName).apply { writeBytes(MinimalPngFixtures.indexed4x4()) }
        val output = File(outDir, outputName)

        val result = image.compress(input.absolutePath, output.absolutePath)
        assertTrue(
            result.isSuccess,
            "Image compress must succeed for encoded path '$subdir/$inputName' → " +
                "'$subdir-out/$outputName'. Got: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
    }

    private suspend fun runAudioAt(subdir: String, inputName: String, outputName: String) {
        val inDir = File(tempDir, subdir).apply { mkdirs() }
        val outDir = File(tempDir, "$subdir-out").apply { mkdirs() }
        val input = File(inDir, inputName)
        AudioInputFixtures.createAacM4a(
            input,
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
            bitrate = 96_000,
        )
        val output = File(outDir, outputName)

        val result = audio.compress(input.absolutePath, output.absolutePath)
        assertTrue(
            result.isSuccess,
            "Audio compress must succeed for encoded path '$subdir/$inputName' → " +
                "'$subdir-out/$outputName'. Got: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
    }

    private suspend fun runVideoAt(subdir: String, inputName: String, outputName: String) {
        val inDir = File(tempDir, subdir).apply { mkdirs() }
        val outDir = File(tempDir, "$subdir-out").apply { mkdirs() }
        val input = File(inDir, inputName)
        Mp4Generator.generateMp4(input, frameCount = VIDEO_FRAME_COUNT)
        val output = File(outDir, outputName)

        val result = video.compress(input.absolutePath, output.absolutePath)
        assertTrue(
            result.isSuccess,
            "Video compress must succeed for encoded path '$subdir/$inputName' → " +
                "'$subdir-out/$outputName'. Got: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
    }

    private companion object {
        const val VIDEO_FRAME_COUNT = 15
    }
}
