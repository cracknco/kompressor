/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor.video

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.of
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
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

    @Test
    fun thumbnail_bytesInput_bitwiseIdenticalToFilePath() = runTest {
        // Mirrors the Stream sibling above for the `MediaSource.Local.Bytes` materialization
        // path. `Bytes` flows through the same `materializeToTempFile` resolver as `Stream`,
        // but distinct test exercise guards against a code-path-specific regression (e.g. the
        // resolver dispatching Bytes to a different temp-name strategy that breaks reads).
        val legacyOut = File(tempDir, "legacy_bytes.jpg")
        val bytesOut = File(tempDir, "bytes_bytes.jpg")

        val legacy = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(legacyOut.absolutePath),
        )
        val bytes = compressor.thumbnail(
            MediaSource.Local.Bytes(inputFile.readBytes()),
            MediaDestination.Local.FilePath(bytesOut.absolutePath),
        )

        assertTrue(legacy.isSuccess, "legacy thumbnail() failed: ${legacy.exceptionOrNull()}")
        assertTrue(bytes.isSuccess, "bytes thumbnail() failed: ${bytes.exceptionOrNull()}")
        assertTrue(
            bytesOut.readBytes().contentEquals(legacyOut.readBytes()),
            "Bytes input must produce byte-identical output to legacy FilePath",
        )
    }

    @Test
    fun thumbnail_fileUriInput_producesValidJpegFrame() = runTest {
        // Exercises the `MediaSource.of(Uri)` builder with a `file://` Uri — the
        // `AndroidUriMediaSource` resolver hands the fixture's filesystem path back through
        // `Context.contentResolver.openInputStream(uri)` (no temp materialization for `file://`).
        // Locks the file-Uri → `thumbnailFilePath` handoff so a regression in the URI resolver
        // wouldn't ship silently behind the FilePath-only test matrix.
        val output = File(tempDir, "thumb_uri.jpg")
        val fileUri = Uri.fromFile(inputFile)

        val result = compressor.thumbnail(
            MediaSource.of(fileUri),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isSuccess, "thumbnail(file://) failed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be a valid JPEG")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, opts)
        assertEquals(INPUT_WIDTH, opts.outWidth, "Thumbnail must keep source width by default")
        assertEquals(INPUT_HEIGHT, opts.outHeight, "Thumbnail must keep source height by default")
    }

    @Test
    fun thumbnail_audioOnlyMp4_returnsUnsupportedSourceFormat() = runTest {
        // Parity with `compress()`'s `videoOnlyNoAudioTrack_compressesSuccessfully` sibling:
        // audio-only MP4s must surface the same `VideoCompressionError.UnsupportedSourceFormat`
        // through both `compress()` and `thumbnail()`. Without the pre-flight guard the call
        // surfaces `DecodingFailed` from `getScaledFrameAtTime` instead — invisible to
        // consumers `when`-branching on the typed hierarchy.
        val audioOnly = File(tempDir, "audio_only.mp4")
        AudioInputFixtures.createAacM4a(
            audioOnly,
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
            bitrate = 96_000,
        )
        val output = File(tempDir, "thumb_audio_only.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(audioOnly.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(result.isFailure, "Audio-only MP4 must be rejected")
        val error = result.exceptionOrNull()
        assertTrue(
            error is VideoCompressionError.UnsupportedSourceFormat,
            "Expected UnsupportedSourceFormat, got ${error?.let { it::class.simpleName }}: $error",
        )
        assertTrue(!output.exists(), "No partial output should leak on failure")
    }

    @Test
    fun thumbnail_atMillisEqualsDuration_succeeds() = runTest {
        // Boundary check: the implementation uses strict `>` comparison, so `atMillis == duration`
        // must succeed (last frame of a 1s fixture is at the duration boundary). Reading the
        // fixture's actual probed duration keeps the test self-contained — the encoder's
        // 30-frames-at-30fps doesn't always produce exactly 1000 ms in the container.
        val durationMs = readDurationMillis(inputFile)
        val output = File(tempDir, "thumb_boundary.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            atMillis = durationMs,
        )

        assertTrue(
            result.isSuccess,
            "atMillis == duration ($durationMs) must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(OutputValidators.isValidJpeg(output.readBytes()), "Output must be a valid JPEG")
    }

    @Test
    fun thumbnail_atMillisEqualsDurationPlusOne_returnsTimestampOutOfRange() = runTest {
        // Boundary check: `duration + 1` must trip the `>` guard and produce
        // `TimestampOutOfRange`. Pairs with the equal-to-duration test above to lock the
        // strict-inequality contract against off-by-one regressions.
        val durationMs = readDurationMillis(inputFile)
        val output = File(tempDir, "thumb_boundary_overshoot.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(inputFile.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            atMillis = durationMs + 1,
        )

        assertTrue(result.isFailure, "atMillis == duration + 1 must fail")
        val error = result.exceptionOrNull()
        assertTrue(
            error is VideoCompressionError.TimestampOutOfRange,
            "Expected TimestampOutOfRange, got ${error?.let { it::class.simpleName }}: $error",
        )
        assertTrue(!output.exists(), "No partial output should leak on failure")
    }

    @Test
    fun thumbnail_1080pSource_keepsHeapDeltaUnderBound() = runTest {
        // Verifies that `getScaledFrameAtTime` (API 27+) actually bounds memory during decode —
        // a regression to the `getFrameAtTime` + post-decode resize path would allocate the
        // full 1080p frame (~8 MB / Bitmap.Config.ARGB_8888) plus the downscaled copy on the
        // heap simultaneously. The bound is intentionally generous (75 MB) so noisy CI runners
        // don't false-fail; an unbounded full-frame allocation on a 4K source would burn ≥30 MB
        // and is the actual regression class we're guarding against.
        val source1080p = Mp4Generator.generateMp4(
            output = File(tempDir, "1080p.mp4"),
            width = WIDTH_1080P,
            height = HEIGHT_1080P,
            frameCount = INPUT_FPS,
            fps = INPUT_FPS,
        )
        val output = File(tempDir, "thumb_1080p.jpg")

        // Drain finalizers + GC twice before sampling to settle the JVM baseline. Single GC is
        // documented unreliable for this use case (Hotspot may defer reclamation).
        val runtime = Runtime.getRuntime()
        repeat(2) {
            runtime.gc()
            runtime.runFinalization()
        }
        val baselineUsed = runtime.totalMemory() - runtime.freeMemory()

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(source1080p.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = THUMB_MAX_DIM,
        )

        assertTrue(result.isSuccess, "1080p thumbnail() failed: ${result.exceptionOrNull()}")
        val peakUsed = runtime.totalMemory() - runtime.freeMemory()
        val deltaBytes = (peakUsed - baselineUsed).coerceAtLeast(0)
        val deltaMb = deltaBytes / BYTES_PER_MB
        assertTrue(
            deltaMb < MAX_HEAP_DELTA_MB,
            "Heap delta ${deltaMb}MB must be under ${MAX_HEAP_DELTA_MB}MB (baseline=" +
                "${baselineUsed / BYTES_PER_MB}MB peak=${peakUsed / BYTES_PER_MB}MB)",
        )
    }

    private fun readDurationMillis(file: File): Long {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            return checkNotNull(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            ) { "Fixture has no readable duration: $file" }
        } finally {
            mmr.release()
        }
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
        const val WIDTH_1080P = 1920
        const val HEIGHT_1080P = 1080
        const val THUMB_MAX_DIM = 256
        const val BYTES_PER_MB = 1024L * 1024L
        const val MAX_HEAP_DELTA_MB = 75L
    }
}
