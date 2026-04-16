/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.MinimalPngFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * iOS mirror of `androidDeviceTest/.../PathEncodingTest.kt` — verifies each compressor round-trips
 * paths with spaces, accented characters, CJK characters, and emoji.
 */
class PathEncodingTest {

    private lateinit var tempDir: String
    private val image = IosImageCompressor()
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "kompressor-path-encoding-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
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
        runAudioAt("sub dir", "in put.wav", "out put.m4a")
    }

    @Test
    fun audioCompress_pathWithAccentedChars_succeeds() = runTest {
        runAudioAt("café", "résumé.wav", "sortie.m4a")
    }

    @Test
    fun audioCompress_pathWithCjkChars_succeeds() = runTest {
        runAudioAt("测试", "文件.wav", "输出.m4a")
    }

    @Test
    fun audioCompress_pathWithEmoji_succeeds() = runTest {
        runAudioAt("\uD83C\uDFB5dir", "\uD83C\uDFB6song.wav", "\uD83C\uDFB5out.m4a")
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
        val inDir = tempDir + "$subdir/"
        val outDir = tempDir + "$subdir-out/"
        makeDirs(inDir)
        makeDirs(outDir)
        val input = inDir + inputName
        val output = outDir + outputName
        writeBytes(input, MinimalPngFixtures.indexed4x4())

        val result = image.compress(input, output)
        assertTrue(
            result.isSuccess,
            "Image compress must succeed for encoded path '$subdir/$inputName' → " +
                "'$subdir-out/$outputName'. Got: ${result.exceptionOrNull()}",
        )
        assertTrue(fileSize(output) > 0)
    }

    private suspend fun runAudioAt(subdir: String, inputName: String, outputName: String) {
        val inDir = tempDir + "$subdir/"
        val outDir = tempDir + "$subdir-out/"
        makeDirs(inDir)
        makeDirs(outDir)
        val input = inDir + inputName
        val output = outDir + outputName
        writeBytes(
            input,
            WavGenerator.generateWavBytes(
                durationSeconds = 1,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
            ),
        )

        val result = audio.compress(input, output)
        assertTrue(
            result.isSuccess,
            "Audio compress must succeed for encoded path '$subdir/$inputName' → " +
                "'$subdir-out/$outputName'. Got: ${result.exceptionOrNull()}",
        )
        assertTrue(fileSize(output) > 0)
    }

    private suspend fun runVideoAt(subdir: String, inputName: String, outputName: String) {
        val inDir = tempDir + "$subdir/"
        val outDir = tempDir + "$subdir-out/"
        makeDirs(inDir)
        makeDirs(outDir)
        val input = inDir + inputName
        val output = outDir + outputName
        Mp4Generator.generateMp4(input, frameCount = VIDEO_FRAME_COUNT)

        val result = video.compress(input, output)
        assertTrue(
            result.isSuccess,
            "Video compress must succeed for encoded path '$subdir/$inputName' → " +
                "'$subdir-out/$outputName'. Got: ${result.exceptionOrNull()}",
        )
        assertTrue(fileSize(output) > 0)
    }

    private fun makeDirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    private companion object {
        const val VIDEO_FRAME_COUNT = 15
    }
}
