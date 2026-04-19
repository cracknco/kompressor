/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.MinimalPngFixtures
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * iOS mirror of `androidDeviceTest/.../ConcurrentCompressionTest.kt`. Confirms that 4 parallel
 * audio exports, a 2+2 audio/image mix, and a 16-coroutine stress grid all finish successfully
 * with distinct outputs. See `docs/threading-model.md`.
 */
class ConcurrentCompressionTest {

    private lateinit var tempDir: String
    private val audio = IosAudioCompressor()
    private val image = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "kompressor-concurrent-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
    }

    @Test
    fun fourParallelAudioCompressions_allSucceed() = runBlocking {
        val inputs = (0 until PARALLEL_AUDIO_COUNT).map { i -> writeWav(i) }
        val outputs = (0 until PARALLEL_AUDIO_COUNT).map { i -> tempDir + "audio_out_$i.m4a" }

        val results = coroutineScope {
            inputs.zip(outputs).map { (inPath, outPath) ->
                async(Dispatchers.Default) { audio.compress(inPath, outPath) }
            }.awaitAll()
        }

        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "Parallel audio #$i failed: ${r.exceptionOrNull()}")
        }
        outputs.forEachIndexed { i, out ->
            assertTrue(fileSize(out) > 0, "Output #$i is empty")
        }
    }

    @Test
    fun mixedAudioAndImageCompressions_allSucceed() = runBlocking {
        val audioInputs = (0 until MIXED_AUDIO_COUNT).map { i -> writeWav(i) }
        val audioOutputs = (0 until MIXED_AUDIO_COUNT).map { i -> tempDir + "mixed_audio_$i.m4a" }
        val imageInputs = (0 until MIXED_IMAGE_COUNT).map { i ->
            val path = tempDir + "img_$i.png"
            writeBytes(path, MinimalPngFixtures.indexed4x4())
            path
        }
        val imageOutputs = (0 until MIXED_IMAGE_COUNT).map { i -> tempDir + "mixed_image_$i.jpg" }

        val results = coroutineScope {
            val audioDeferreds = audioInputs.zip(audioOutputs).map { (i, o) ->
                async(Dispatchers.Default) { audio.compress(i, o) }
            }
            val imageDeferreds = imageInputs.zip(imageOutputs).map { (i, o) ->
                async(Dispatchers.Default) { image.compress(i, o) }
            }
            (audioDeferreds + imageDeferreds).awaitAll()
        }

        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "Parallel mixed #$i failed: ${r.exceptionOrNull()}")
        }
        (audioOutputs + imageOutputs).forEachIndexed { i, out ->
            assertTrue(fileSize(out) > 0, "Mixed output #$i empty")
        }
    }

    @Test
    fun sixteenParallelCoroutines_allSucceed() = runBlocking {
        val inputs = (0 until STRESS_COUNT).map { i -> writeStressWav(i) }
        val outputs = (0 until STRESS_COUNT).map { i -> tempDir + "stress_audio_out_$i.m4a" }

        val results = coroutineScope {
            inputs.zip(outputs).map { (inPath, outPath) ->
                async(Dispatchers.Default) { audio.compress(inPath, outPath) }
            }.awaitAll()
        }

        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "Stress audio #$i failed: ${r.exceptionOrNull()}")
        }
        outputs.forEachIndexed { i, out ->
            assertTrue(fileSize(out) > 0, "Stress output #$i empty")
        }
    }

    private fun writeWav(index: Int): String = writeWavAt("audio_in_$index.wav")

    private fun writeStressWav(index: Int): String = writeWavAt("stress_audio_in_$index.wav")

    private fun writeWavAt(fileName: String): String {
        val path = tempDir + fileName
        writeBytes(
            path,
            WavGenerator.generateWavBytes(
                durationSeconds = 1,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
            ),
        )
        return path
    }

    private companion object {
        const val PARALLEL_AUDIO_COUNT = 4
        const val MIXED_AUDIO_COUNT = 2
        const val MIXED_IMAGE_COUNT = 2
        const val STRESS_COUNT = 16
    }
}
