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
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * CRA-50 — iOS sibling of `CompressionLeakTest`.
 *
 * Runs 50 iterations of each modality against the iOS compressors so the process can be
 * observed externally by `xctrace record --template Leaks` (driven by
 * `scripts/ios-leak-test.sh` from the `ios-leak-tests` CI job). Unlike the Android side,
 * iOS has no in-process leak-detection library equivalent to LeakCanary — the assertion
 * lives in the CI script: the Leaks instrument produces a trace, the script exports it
 * to a table, and counts rows. Zero = green, anything else = red.
 *
 * Why this test lives in `iosTest` and not a standalone runner:
 *   - Kotlin/Native iOS simulator tests already compile to a `test.kexe` Mach-O binary
 *     that `xctrace --launch` can drive. Reusing the existing test infrastructure avoids
 *     a bespoke Xcode project just for leak telemetry.
 *   - The compressors exercised here are identical to the ones validated elsewhere in
 *     `iosTest/` — same API surface, same platform calls (`AVAssetExportSession`,
 *     `AVAssetWriter`, `UIImage` → `CGImageDestinationFinalize`).
 *
 * Iteration count mirrors the Android side (50) for parity.
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
    fun imageCompression_50Iterations_exercisesCoreGraphicsLifecycle() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_DIM, IMAGE_DIM)

        repeat(ITERATIONS) { i ->
            // New compressor per iteration so xctrace's Leaks instrument has a chance to
            // catch retained instances between `release()` points. If `IosImageCompressor`
            // leaks its Core Graphics bitmap context, Leaks picks it up in the final dump.
            val compressor = IosImageCompressor()
            val outputPath = testDir + "img_$i.jpg"
            val result = compressor.compress(
                inputPath = inputPath,
                outputPath = outputPath,
                config = ImageCompressionConfig(quality = DEFAULT_QUALITY),
            )
            check(result.isSuccess) { "Image iter $i failed: ${result.exceptionOrNull()}" }
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    @Test
    fun audioCompression_50Iterations_exercisesAvAssetWriterLifecycle() = runTest {
        val inputPath = testDir + "audio_input.wav"
        writeBytes(
            inputPath,
            WavGenerator.generateWavBytes(
                durationSeconds = AUDIO_FIXTURE_SECONDS,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
            ),
        )

        repeat(ITERATIONS) { i ->
            val compressor = IosAudioCompressor()
            val outputPath = testDir + "audio_$i.m4a"
            val result = compressor.compress(
                inputPath = inputPath,
                outputPath = outputPath,
                config = AudioCompressionConfig(bitrate = AUDIO_BITRATE),
            )
            check(result.isSuccess) { "Audio iter $i failed: ${result.exceptionOrNull()}" }
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    @Test
    fun videoCompression_50Iterations_exercisesAvAssetExportSessionLifecycle() = runTest {
        val inputPath = Mp4Generator.generateMp4(
            outputPath = testDir + "video_input.mp4",
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            frameCount = VIDEO_FRAME_COUNT,
        )

        repeat(ITERATIONS) { i ->
            val compressor = IosVideoCompressor()
            val outputPath = testDir + "video_$i.mp4"
            val result = compressor.compress(
                inputPath = inputPath,
                outputPath = outputPath,
                config = VideoCompressionConfig(),
            )
            check(result.isSuccess) { "Video iter $i failed: ${result.exceptionOrNull()}" }
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    private companion object {
        /** Mirror of `CompressionLeakTest.ITERATIONS` on Android. */
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
