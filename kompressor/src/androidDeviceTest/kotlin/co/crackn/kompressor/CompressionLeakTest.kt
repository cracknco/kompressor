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
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import kotlinx.coroutines.test.runTest
import leakcanary.AppWatcher
import leakcanary.LeakAssertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * CRA-50 — Retention sentinel for the three native compression pipelines.
 *
 * Why this test exists: `MediaCodec`, `MediaMuxer`, and Media3 `Transformer` all hold large
 * off-heap buffers + hardware encoder sessions. A single missed `release()` on a retry path
 * is invisible in a one-shot golden test but turns into an OOM in a consumer app after N
 * compressions. Running 50 iterations of each modality and asserting **zero retained
 * instances** detects the regression at PR time instead of in the field.
 *
 * How it works:
 *   1. LeakCanary's `AppWatcher.objectWatcher.expectWeaklyReachable(compressor, "…")` marks
 *      each compressor instance as "should be GC'd after this point".
 *   2. After the loop runs, [LeakAssertions.assertNoLeaks] dumps the heap and asks Shark to
 *      walk the retention chain. Any still-retained compressor fails the test and surfaces
 *      the chain in the test report — this is the "CI rouge" gate called out in the DoD.
 *
 * Iteration count is deliberately 50 (not 100+): hardware encoder startup on CI emulators
 * is the bottleneck; 50 is enough to observe any cumulative leak while keeping the runtime
 * under a minute on ARM64 emulator instances.
 */
class CompressionLeakTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-leak-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun imageCompression_50Iterations_hasNoLeaks() = runTest {
        // Single source fixture — generating 50 distinct fixtures would swamp the emulator's
        // disk without adding coverage. Input reuse is orthogonal to the leak surface.
        val input = createTestImage(tempDir, IMAGE_DIM, IMAGE_DIM)

        repeat(ITERATIONS) { i ->
            val compressor = AndroidImageCompressor()
            AppWatcher.objectWatcher.expectWeaklyReachable(
                compressor,
                "AndroidImageCompressor iteration $i should be GC-able after compress() returns",
            )
            val output = File(tempDir, "img_$i.jpg")
            val result = compressor.compress(
                inputPath = input.absolutePath,
                outputPath = output.absolutePath,
                config = ImageCompressionConfig(quality = DEFAULT_QUALITY),
            )
            check(result.isSuccess) { "Image iter $i failed: ${result.exceptionOrNull()}" }
            output.delete()
        }

        LeakAssertions.assertNoLeaks("AndroidImageCompressor should not retain after $ITERATIONS iterations")
    }

    @Test
    fun audioCompression_50Iterations_hasNoLeaks() = runTest {
        // Reuse a single AAC-in-M4A fixture. AAC-passthrough hits a different code path than
        // transcode, but leak pressure comes from the Media3 `Transformer` Tool lifecycle —
        // exercised identically on both paths. One fixture is enough.
        val input = AudioInputFixtures.createAacM4a(
            output = File(tempDir, "audio_input.m4a"),
            durationSeconds = AUDIO_FIXTURE_SECONDS,
        )

        repeat(ITERATIONS) { i ->
            val compressor = AndroidAudioCompressor()
            AppWatcher.objectWatcher.expectWeaklyReachable(
                compressor,
                "AndroidAudioCompressor iteration $i should be GC-able after compress() returns",
            )
            val output = File(tempDir, "audio_$i.m4a")
            val result = compressor.compress(
                inputPath = input.absolutePath,
                outputPath = output.absolutePath,
                config = AudioCompressionConfig(bitrate = AUDIO_BITRATE),
            )
            check(result.isSuccess) { "Audio iter $i failed: ${result.exceptionOrNull()}" }
            output.delete()
        }

        LeakAssertions.assertNoLeaks("AndroidAudioCompressor should not retain after $ITERATIONS iterations")
    }

    @Test
    fun videoCompression_50Iterations_hasNoLeaks() = runTest {
        // Short 1-second clip at modest resolution: Media3 Transformer lifecycle is the leak
        // surface, not frame count. Minimising per-iteration work keeps the emulator test
        // under the CI timeout budget.
        val input = Mp4Generator.generateMp4(
            output = File(tempDir, "video_input.mp4"),
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            frameCount = VIDEO_FRAME_COUNT,
        )

        repeat(ITERATIONS) { i ->
            val compressor = AndroidVideoCompressor()
            AppWatcher.objectWatcher.expectWeaklyReachable(
                compressor,
                "AndroidVideoCompressor iteration $i should be GC-able after compress() returns",
            )
            val output = File(tempDir, "video_$i.mp4")
            val result = compressor.compress(
                inputPath = input.absolutePath,
                outputPath = output.absolutePath,
                config = VideoCompressionConfig(),
            )
            check(result.isSuccess) { "Video iter $i failed: ${result.exceptionOrNull()}" }
            output.delete()
        }

        LeakAssertions.assertNoLeaks("AndroidVideoCompressor should not retain after $ITERATIONS iterations")
    }

    private companion object {
        /**
         * CRA-50 DoD: "50 compressions". Balanced against CI emulator throughput — hardware
         * encoder startup dominates the per-iteration cost on the ARM64 emulators we ship to.
         */
        const val ITERATIONS = 50
        const val IMAGE_DIM = 512
        const val DEFAULT_QUALITY = 75
        const val AUDIO_FIXTURE_SECONDS = 1
        const val AUDIO_BITRATE = 64_000
        const val VIDEO_WIDTH = 320
        const val VIDEO_HEIGHT = 240
        const val VIDEO_FRAME_COUNT = 10
    }
}
