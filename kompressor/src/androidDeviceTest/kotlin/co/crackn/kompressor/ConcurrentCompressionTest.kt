/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.MinimalPngFixtures
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Concurrent-compression smoke tests. Four parallel audio exports, a 2+2 audio/image mix, and a
 * 16-coroutine stress grid — all must succeed with non-empty outputs to confirm there are no
 * shared mutable-state or temp-file races between sibling coroutines. The 16-coroutine variant
 * mirrors the grid shape of [ConcurrentCompressInterProcessTest] on the host JVM so a
 * dispatcher-level lock would be visible here in real Media3 `Transformer` / `MediaCodec`
 * conditions. See `docs/threading-model.md`.
 */
class ConcurrentCompressionTest {

    private lateinit var tempDir: File
    private val audio = AndroidAudioCompressor()
    private val image = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-concurrent-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun fourParallelAudioCompressions_allSucceed() = runBlocking {
        val inputs = (0 until PARALLEL_AUDIO_COUNT).map { i -> wavFile(i) }
        val outputs = (0 until PARALLEL_AUDIO_COUNT).map { i -> File(tempDir, "audio_out_$i.m4a") }

        val results = coroutineScope {
            inputs.zip(outputs).map { (input, output) ->
                async(Dispatchers.Default) {
                    audio.compress(
                        MediaSource.Local.FilePath(input.absolutePath),
                        MediaDestination.Local.FilePath(output.absolutePath),
                    )
                }
            }.awaitAll()
        }

        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "Parallel audio #$i failed: ${r.exceptionOrNull()}")
        }
        outputs.forEachIndexed { i, out ->
            assertTrue(out.exists() && out.length() > 0, "Output #$i is missing / empty")
        }
    }

    @Test
    fun mixedAudioAndImageCompressions_allSucceed() = runBlocking {
        val audioInputs = (0 until MIXED_AUDIO_COUNT).map { i -> wavFile(i) }
        val audioOutputs = (0 until MIXED_AUDIO_COUNT).map { i ->
            File(tempDir, "mixed_audio_$i.m4a")
        }
        val imageInputs = (0 until MIXED_IMAGE_COUNT).map { i ->
            File(tempDir, "img_$i.png").apply {
                writeBytes(MinimalPngFixtures.indexed4x4())
            }
        }
        val imageOutputs = (0 until MIXED_IMAGE_COUNT).map { i ->
            File(tempDir, "mixed_image_$i.jpg")
        }

        val results = coroutineScope {
            val audioDeferreds = audioInputs.zip(audioOutputs).map { (i, o) ->
                async(Dispatchers.Default) {
                    audio.compress(
                        MediaSource.Local.FilePath(i.absolutePath),
                        MediaDestination.Local.FilePath(o.absolutePath),
                    )
                }
            }
            val imageDeferreds = imageInputs.zip(imageOutputs).map { (i, o) ->
                async(Dispatchers.Default) {
                    image.compress(
                        MediaSource.Local.FilePath(i.absolutePath),
                        MediaDestination.Local.FilePath(o.absolutePath),
                    )
                }
            }
            (audioDeferreds + imageDeferreds).awaitAll()
        }

        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "Parallel mixed #$i failed: ${r.exceptionOrNull()}")
        }
        (audioOutputs + imageOutputs).forEachIndexed { i, out ->
            assertTrue(out.exists() && out.length() > 0, "Mixed output #$i missing / empty")
        }
    }

    @Test
    fun sixteenParallelCoroutines_allSucceed() = runBlocking {
        val inputs = (0 until STRESS_COUNT).map { i ->
            File(tempDir, "stress_audio_in_$i.wav").apply {
                writeBytes(
                    WavGenerator.generateWavBytes(
                        durationSeconds = 1,
                        sampleRate = SAMPLE_RATE_44K,
                        channels = STEREO,
                    ),
                )
            }
        }
        val outputs = (0 until STRESS_COUNT).map { i ->
            File(tempDir, "stress_audio_out_$i.m4a")
        }

        val results = coroutineScope {
            inputs.zip(outputs).map { (input, output) ->
                async(Dispatchers.Default) {
                    audio.compress(
                        MediaSource.Local.FilePath(input.absolutePath),
                        MediaDestination.Local.FilePath(output.absolutePath),
                    )
                }
            }.awaitAll()
        }

        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "Stress audio #$i failed: ${r.exceptionOrNull()}")
        }
        outputs.forEachIndexed { i, out ->
            assertTrue(out.exists() && out.length() > 0, "Stress output #$i missing / empty")
        }
    }

    private fun wavFile(index: Int): File = File(tempDir, "audio_in_$index.wav").apply {
        writeBytes(
            WavGenerator.generateWavBytes(
                durationSeconds = 1,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
            ),
        )
    }

    private companion object {
        const val PARALLEL_AUDIO_COUNT = 4
        const val MIXED_AUDIO_COUNT = 2
        const val MIXED_IMAGE_COUNT = 2
        const val STRESS_COUNT = 16
    }
}
