/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import kotlinx.cinterop.ExperimentalForeignApi
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoPresets
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosVideoCompressorTest {

    private lateinit var testDir: String
    private lateinit var inputPath: String
    private val compressor = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-video-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        inputPath = Mp4Generator.generateMp4(
            outputPath = testDir + "input.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAMES,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressVideo_producesValidMp4() = runTest {
        val outputPath = testDir + "output.mp4"

        val result = compressor.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(outputPath),
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        assertTrue(
            OutputValidators.isValidMp4(readBytes(outputPath)),
            "Output should be valid MP4",
        )
    }

    @Test
    fun compressVideo_downscalesTo480p() = runTest {
        val outputPath = testDir + "480p.mp4"
        val config = VideoCompressionConfig(maxResolution = MaxResolution.SD_480)

        val result = compressor.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(outputPath),
            config,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
    }

    @Test
    fun compressVideo_customConfig_producesValidOutput() = runTest {
        val outputPath = testDir + "custom.mp4"

        val result = compressor.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(outputPath),
            VideoCompressionConfig(videoBitrate = 500_000),
        )

        assertTrue(result.isSuccess, "Custom config failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
        assertTrue(result.getOrThrow().outputSize > 0)
    }

    @Test
    fun compressVideo_progressReported() = runTest {
        val outputPath = testDir + "progress.mp4"
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(outputPath),
            onProgress = { progressValues.add(it.fraction) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first(), 1e-6f)
        assertEquals(1f, progressValues.last(), 1e-6f)
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1], "Progress should be monotonic")
        }
    }

    @Test
    fun compressVideo_fileNotFound_returnsFailure() = runTest {
        val result = compressor.compress(
            MediaSource.Local.FilePath("/nonexistent/video.mp4"),
            MediaDestination.Local.FilePath(testDir + "out.mp4"),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun cancellation_deletesPartialOutput() = kotlinx.coroutines.runBlocking {
        // iOS mirror of the video cancellation test. A 300-frame fixture ensures the export
        // is mid-copy when cancel lands; `IosVideoTranscodePipeline.copySamples` yields on
        // every buffer via `currentCoroutineContext().ensureActive()`, so the first yield
        // after cancel throws `CancellationException`. The `deletingOutputOnFailure` wrapper
        // (iosMain) must then remove the partial .mp4 before the coroutine unwinds.
        val longInputPath = Mp4Generator.generateMp4(
            outputPath = testDir + "cancel_input.mp4",
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = 300,
            fps = INPUT_FPS,
        )
        val outputPath = testDir + "cancelled_video.mp4"
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job(),
        )
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            compressor.compress(
                MediaSource.Local.FilePath(longInputPath),
                MediaDestination.Local.FilePath(outputPath),
                onProgress = { p -> if (p.fraction > 0f && !started.isCompleted) started.complete(Unit) },
            )
        }
        kotlinx.coroutines.withTimeout(5_000L) { started.await() }
        job.cancel()
        kotlinx.coroutines.withTimeout(15_000L) { job.join() }

        assertTrue(job.isCancelled, "Job must be cancelled to validate partial-output cleanup")
        assertTrue(
            !NSFileManager.defaultManager.fileExistsAtPath(outputPath),
            "Cancelled iOS video export must delete its partial output",
        )
    }

    @Test
    fun compressVideo_malformedInput_gracefulError() = runTest {
        // Mirror of the Android test: garbage bytes written to an `.mp4` path must produce a
        // clean `Result.failure`, not a crash. Exercises the error-wrapping path in
        // `IosVideoCompressor` when `AVURLAsset` / `AVAssetReader` cannot open the input.
        val garbagePath = testDir + "garbage_video.mp4"
        writeBytes(garbagePath, ByteArray(256) { 0xFF.toByte() })
        val outputPath = testDir + "out_from_garbage.mp4"

        val result = compressor.compress(
            MediaSource.Local.FilePath(garbagePath),
            MediaDestination.Local.FilePath(outputPath),
        )

        assertTrue(result.isFailure, "Malformed MP4 must fail cleanly, not crash")
    }

    @Test
    fun compressVideo_messagingPreset_producesValidOutput() = runTest {
        val outputPath = testDir + "messaging.mp4"
        val result = compressor.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(outputPath),
            VideoPresets.MESSAGING,
        )
        assertTrue(result.isSuccess, "Messaging preset failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidMp4(readBytes(outputPath)))
    }

    private companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        const val INPUT_FRAMES = 30
        const val INPUT_FPS = 30
    }
}
