/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor.video

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests for [AndroidVideoCompressor.thumbnail] against real MP4 fixtures generated
 * by [Mp4Generator]. Covers the happy path (valid JPEG + correct dimensions), optional
 * `maxDimension` downscale, rotation preservation, typed [VideoCompressionError] for
 * out-of-range timestamps, and the `require()` programmer-error contract.
 */
class VideoThumbnailDeviceTest {

    private lateinit var tempDir: File
    private lateinit var inputFile: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // UUID suffix mirrors the iOS sibling's `NSUUID().UUIDString` and future-proofs against
        // parallel test-class sharding under `testInstrumentationRunnerArguments`.
        tempDir = File(
            context.cacheDir,
            "kompressor-video-thumbnail-test-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        inputFile = Mp4Generator.generateMp4(
            output = File(tempDir, "input.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            fps = INPUT_FPS,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun thumbnail_defaultArgs_producesValidJpegFrameAtZero() = runTest {
        val output = File(tempDir, "thumb_default.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess, "thumbnail() failed: ${result.exceptionOrNull()}")
        assertTrue(output.exists())
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be a valid JPEG")
        val compression = result.getOrThrow()
        // inputSize is the source video's byte count; outputSize is the encoded still.
        assertTrue(compression.inputSize == inputFile.length())
        assertTrue(compression.outputSize == output.length())
        assertTrue(compression.durationMs >= 0)

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, opts)
        assertEquals(INPUT_WIDTH, opts.outWidth, "Thumbnail must keep source width by default")
        assertEquals(INPUT_HEIGHT, opts.outHeight, "Thumbnail must keep source height by default")
    }

    @Test
    fun thumbnail_maxDimensionCapsLongerEdge() = runTest {
        val output = File(tempDir, "thumb_scaled.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIM,
        )

        assertTrue(result.isSuccess, "thumbnail() failed: ${result.exceptionOrNull()}")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, opts)
        val longer = maxOf(opts.outWidth, opts.outHeight)
        assertTrue(
            longer <= MAX_DIM,
            "Longer edge $longer must be <= $MAX_DIM (got ${opts.outWidth}x${opts.outHeight})",
        )
        // No upscale — shorter edge ratio must match the aspect ratio of the source.
        val aspect = INPUT_WIDTH.toDouble() / INPUT_HEIGHT
        val outAspect = opts.outWidth.toDouble() / opts.outHeight
        assertTrue(kotlin.math.abs(aspect - outAspect) < ASPECT_TOLERANCE)
    }

    @Test
    fun thumbnail_portraitFixture_producesPortraitThumbnail() = runTest {
        val rotated = Mp4Generator.generateMp4(
            output = File(tempDir, "portrait.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
            rotationDegrees = PORTRAIT_ROTATION,
        )
        val output = File(tempDir, "thumb_portrait.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(rotated.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess, "thumbnail() failed: ${result.exceptionOrNull()}")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, opts)
        // Source is a 640x480 landscape buffer tagged rotate-90 so the displayed frame is
        // 480x640 (portrait). `MediaMetadataRetriever` applies the rotation tag during decode
        // so the extracted bitmap must come out portrait.
        assertTrue(
            opts.outHeight > opts.outWidth,
            "Portrait thumbnail must have height > width (got ${opts.outWidth}x${opts.outHeight})",
        )
    }

    @Test
    fun thumbnail_webpFormat_producesValidOutput() = runTest {
        val output = File(tempDir, "thumb.webp")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            format = ImageFormat.WEBP,
        )

        assertTrue(result.isSuccess, "thumbnail(WEBP) failed: ${result.exceptionOrNull()}")
        assertTrue(output.length() > 0)
        // WebP magic: RIFF....WEBP — enough to confirm the encoder path ran.
        val bytes = output.readBytes()
        val riff = bytes.copyOfRange(0, 4).decodeToString()
        val webp = bytes.copyOfRange(WEBP_MAGIC_OFFSET, WEBP_MAGIC_OFFSET + 4).decodeToString()
        assertEquals("RIFF", riff)
        assertEquals("WEBP", webp)
    }

    @Test
    fun thumbnail_atMillisExceedsDuration_returnsTimestampOutOfRange() = runTest {
        val output = File(tempDir, "thumb_overshoot.jpg")
        // Fixture is 30 frames at 30fps ≈ 1 s. Anything past 10 s is definitely out of range.
        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            atMillis = OVERSHOOT_MILLIS,
        )

        assertTrue(result.isFailure, "Expected failure for out-of-range atMillis")
        val error = result.exceptionOrNull()
        assertTrue(
            error is VideoCompressionError.TimestampOutOfRange,
            "Expected TimestampOutOfRange, got ${error?.let { it::class.simpleName }}: $error",
        )
        // Partial output must not leak on failure — `deletingOutputOnFailure` is the guard.
        assertTrue(!output.exists(), "Output file must be deleted on failure")
    }

    @Test
    fun thumbnail_negativeAtMillis_throwsIllegalArgumentException() = runTest {
        assertFailsWith<IllegalArgumentException> {
            compressor.thumbnail(
                MediaSource.Local.FilePath(inputFile.absolutePath),
                MediaDestination.Local.FilePath(File(tempDir, "never.jpg").absolutePath),
                atMillis = -1L,
            )
        }
    }

    @Test
    fun thumbnail_qualityOutOfRange_throwsIllegalArgumentException() = runTest {
        assertFailsWith<IllegalArgumentException> {
            compressor.thumbnail(
                MediaSource.Local.FilePath(inputFile.absolutePath),
                MediaDestination.Local.FilePath(File(tempDir, "never.jpg").absolutePath),
                quality = INVALID_QUALITY,
            )
        }
    }

    @Test
    fun thumbnail_nonPositiveMaxDimension_throwsIllegalArgumentException() = runTest {
        assertFailsWith<IllegalArgumentException> {
            compressor.thumbnail(
                MediaSource.Local.FilePath(inputFile.absolutePath),
                MediaDestination.Local.FilePath(File(tempDir, "never.jpg").absolutePath),
                maxDimension = 0,
            )
        }
    }

    @Test
    fun thumbnail_streamInput_bitwiseIdenticalToFilePath() = runTest {
        // Locks the `Stream` → `materializeToTempFile` → `thumbnailFilePath` handoff so a
        // regression in the materializer (e.g. early-EOF, wrong content-length) wouldn't ship
        // silently behind the FilePath-only test matrix.
        val legacyOut = File(tempDir, "legacy_stream.jpg")
        val streamOut = File(tempDir, "stream_stream.jpg")

        val legacy = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(legacyOut.absolutePath),
        )
        val stream = compressor.thumbnail(
            MediaSource.Local.Stream(inputFile.inputStream().source(), sizeHint = inputFile.length()),
            MediaDestination.Local.FilePath(streamOut.absolutePath),
        )

        assertTrue(legacy.isSuccess, "legacy thumbnail() failed: ${legacy.exceptionOrNull()}")
        assertTrue(stream.isSuccess, "stream thumbnail() failed: ${stream.exceptionOrNull()}")
        assertTrue(
            streamOut.readBytes().contentEquals(legacyOut.readBytes()),
            "Stream input must produce byte-identical output to legacy FilePath",
        )
    }

    private companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        const val INPUT_FRAME_COUNT = 30
        const val INPUT_FPS = 30
        const val MAX_DIM = 200
        const val ASPECT_TOLERANCE = 0.05
        const val PORTRAIT_ROTATION = 90
        const val OVERSHOOT_MILLIS = 10_000L
        const val INVALID_QUALITY = 150
        const val WEBP_MAGIC_OFFSET = 8
    }
}
