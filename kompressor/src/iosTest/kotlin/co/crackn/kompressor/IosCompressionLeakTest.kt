/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, NativeRuntimeApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * CRA-50 — iOS sibling of `CompressionLeakTest` (Android).
 *
 * Asserts zero retained `Ios{Image,Audio,Video}Compressor` instances after 50 compression
 * iterations — the same "cumulative retention" regression the Android side catches with
 * LeakCanary's `AppWatcher.expectWeaklyReachable` + `assertNoLeaks`.
 *
 * Implementation: [kotlin.native.ref.WeakReference] + [kotlin.native.runtime.GC.collect].
 *
 * Each iteration constructs a compressor in a dedicated non-inline suspend function so the
 * reference is confined to that frame's stack — the frame is popped before the next
 * iteration starts, making the compressor GC-eligible. A `WeakReference` is recorded
 * alongside (weak refs do NOT keep the target alive). After the loop we force two
 * [GC.collect] passes and assert every weak ref is now `null`. Any non-null ref signals a
 * retention path holding the compressor — same class of bug LeakCanary catches on Android,
 * minus the retention-chain printout (use Instruments.app locally to debug a flagged leak).
 *
 * Why this replaces the previous `xctrace record --template Leaks` gate: xctrace depends
 * on Instruments memory-recording services (`VMUTaskMemoryScanner`, `libmalloc`, `vmmap`)
 * whose init is race-y relative to `xcrun simctl boot` on `macos-15-arm64`, producing the
 * recurrent `"Allocations: This device is lacking a required recording service"` flake.
 * This in-process checker has zero external dependencies and cannot flake by design. See
 * `docs/maintainers.md` — "iOS — in-process leak checker".
 */
class IosCompressionLeakTest {

    private lateinit var testDir: String

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-leak-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun imageCompression_50Iterations_hasNoLeaks() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_DIM, IMAGE_DIM)
        // `runIterations` returns ONLY the ref list — the loop's per-iteration compressor
        // locals live exclusively in its stack frame and are popped when it returns. If the
        // loop were inlined here, the last iteration's local would still be live in a
        // register / this-frame slot when `assertAllReleased` runs, producing a
        // false-positive "#49 leaked" even when the compressor is otherwise unreachable.
        val refs = runImageIterations(inputPath)
        assertAllReleased("IosImageCompressor", refs)
    }

    @Test
    fun audioCompression_50Iterations_hasNoLeaks() = runTest {
        val inputPath = testDir + "audio_input.wav"
        writeBytes(
            inputPath,
            WavGenerator.generateWavBytes(
                durationSeconds = AUDIO_FIXTURE_SECONDS,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
            ),
        )
        val refs = runAudioIterations(inputPath)
        assertAllReleased("IosAudioCompressor", refs)
    }

    @Test
    fun videoCompression_50Iterations_hasNoLeaks() = runTest {
        val inputPath = Mp4Generator.generateMp4(
            outputPath = testDir + "video_input.mp4",
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            frameCount = VIDEO_FRAME_COUNT,
        )
        val refs = runVideoIterations(inputPath)
        assertAllReleased("IosVideoCompressor", refs)
    }

    // Discrete suspend functions that own the iteration loop + the ref list. Returning just
    // the list guarantees every iteration-local compressor falls out of scope before the
    // caller (the `@Test` function) reaches `assertAllReleased`. See also the comment on
    // `exerciseXxx` below for the per-iteration stack-frame rationale.

    private suspend fun runImageIterations(
        inputPath: String,
    ): List<WeakReference<IosImageCompressor>> {
        val refs = ArrayList<WeakReference<IosImageCompressor>>(ITERATIONS)
        repeat(ITERATIONS) { i -> exerciseImage(inputPath, i, refs) }
        return refs
    }

    private suspend fun runAudioIterations(
        inputPath: String,
    ): List<WeakReference<IosAudioCompressor>> {
        val refs = ArrayList<WeakReference<IosAudioCompressor>>(ITERATIONS)
        repeat(ITERATIONS) { i -> exerciseAudio(inputPath, i, refs) }
        return refs
    }

    private suspend fun runVideoIterations(
        inputPath: String,
    ): List<WeakReference<IosVideoCompressor>> {
        val refs = ArrayList<WeakReference<IosVideoCompressor>>(ITERATIONS)
        repeat(ITERATIONS) { i -> exerciseVideo(inputPath, i, refs) }
        return refs
    }

    // Each `exerciseXxx` is an explicit non-inline suspend function: the compressor local
    // lives in this frame's stack only. Once the function returns, the frame is popped and
    // the only remaining pointer to the compressor is the `WeakReference` — which does not
    // keep the target alive. If we inlined this inside `repeat { ... }`, the new K/N
    // memory manager could keep the compressor reachable via the enclosing lambda's
    // capture frame until the whole `repeat` block finishes, producing false-positive
    // leak reports. Keeping per-iteration work in a discrete frame is the standard idiom
    // for native leak testing (equivalent to LeakCanary's "lambda scope" rule on JVM).

    private suspend fun exerciseImage(
        inputPath: String,
        i: Int,
        refs: MutableList<WeakReference<IosImageCompressor>>,
    ) {
        val compressor = IosImageCompressor()
        refs.add(WeakReference(compressor))
        val outputPath = testDir + "img_$i.jpg"
        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = ImageCompressionConfig(quality = DEFAULT_QUALITY),
        )
        check(result.isSuccess) { "Image iter $i failed: ${result.exceptionOrNull()}" }
        NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
    }

    private suspend fun exerciseAudio(
        inputPath: String,
        i: Int,
        refs: MutableList<WeakReference<IosAudioCompressor>>,
    ) {
        val compressor = IosAudioCompressor()
        refs.add(WeakReference(compressor))
        val outputPath = testDir + "audio_$i.m4a"
        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(bitrate = AUDIO_BITRATE),
        )
        check(result.isSuccess) { "Audio iter $i failed: ${result.exceptionOrNull()}" }
        NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
    }

    private suspend fun exerciseVideo(
        inputPath: String,
        i: Int,
        refs: MutableList<WeakReference<IosVideoCompressor>>,
    ) {
        val compressor = IosVideoCompressor()
        refs.add(WeakReference(compressor))
        val outputPath = testDir + "video_$i.mp4"
        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = VideoCompressionConfig(),
        )
        check(result.isSuccess) { "Video iter $i failed: ${result.exceptionOrNull()}" }
        NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
    }

    private suspend fun <T : Any> assertAllReleased(label: String, refs: List<WeakReference<T>>) {
        // Three GC passes with a `yield()` between each:
        //
        // - Pass 1 marks reachable objects; anything held only by a popped stack frame becomes
        //   eligible.
        // - `yield()` releases this coroutine so `dispatch_async` / `dispatch_after` blocks
        //   queued by `AVAssetExportSession` / `AVAssetWriter` completion handlers (which may
        //   still hold an implicit Obj-C retain on the compressor) get a chance to drain.
        //   Without this, the final iteration's completion block can outlive the loop.
        // - Pass 2 picks up anything released by the completion handler drain.
        // - Pass 3 drains any still-queued finalizers the second pass created. Empirically
        //   sufficient across image/audio/video on macOS 15 arm64 simulator.
        repeat(GC_PASSES) {
            GC.collect()
            yield()
        }

        val leaked = refs.withIndex().filter { (_, ref) -> ref.get() != null }
        check(leaked.isEmpty()) {
            val first = leaked.take(3).joinToString { "#${it.index}" }
            "Leaked ${leaked.size}/${refs.size} $label instances after $GC_PASSES GC passes. " +
                "First leaked indices: $first. Reproduce locally with " +
                "./gradlew :kompressor:iosSimulatorArm64Test --tests '*IosCompressionLeakTest*' " +
                "then open Instruments.app on the running simulator to inspect the retention chain."
        }
    }

    private companion object {
        /** Mirror of `CompressionLeakTest.ITERATIONS` on Android. */
        const val ITERATIONS = 50

        /** Number of GC.collect passes in [assertAllReleased] — see that function's KDoc. */
        const val GC_PASSES = 3
        const val IMAGE_DIM = 512
        const val DEFAULT_QUALITY = 75
        const val AUDIO_FIXTURE_SECONDS = 1
        const val AUDIO_BITRATE = 64_000
        const val VIDEO_WIDTH = 320
        const val VIDEO_HEIGHT = 240
        const val VIDEO_FRAME_COUNT = 10
    }
}
