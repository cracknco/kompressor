/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageFormat
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createExifTaggedJpeg
import co.crackn.kompressor.testutil.createTestImage
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * On-device end-to-end tests for [AndroidImageCompressor.thumbnail]. Proves the DoD for CRA-108
 * on the Android side end-to-end — the `commonTest` contract + property suites lock down the
 * pure-logic invariants, this class drives the full `BitmapFactory` sampled-decode pipeline on
 * an emulator / physical device.
 *
 * Properties exercised:
 *  * 4000×3000 PNG → ≤ 200×200 JPEG output, aspect ratio preserved to the pixel.
 *  * Peak heap delta during sampled decode stays bounded — a regression that flips the decoder
 *    back to a full-resolution decode (~48 MB) would blow past the 10 MB envelope.
 *  * EXIF orientation = 6 (ROTATE_90) → output dimensions flip orientation and the long edge
 *    stays ≤ maxDimension, mirroring `AndroidImageCompressorTest.exifOrientation_rotate90` at
 *    the thumbnail entry point.
 *  * `maxDimension = 0` → `Result.failure(IllegalArgumentException)`, no output touched.
 */
class ImageThumbnailDeviceTest {

    private lateinit var tempDir: File
    private val compressor = AndroidImageCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-thumbnail-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun thumbnail_largeSource_clampsLongEdgeToMaxDimension() = runTest {
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = File(tempDir, "thumb_4000x3000.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        withClue("Output should be valid JPEG") { OutputValidators.isValidJpeg(output.readBytes()) shouldBe true }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        max(options.outWidth, options.outHeight) shouldBeLessThanOrEqualTo MAX_DIMENSION
        // 4000×3000 (4:3) at maxDim=200 → 200×150 exactly after the exact-resize Pass 2.
        options.outWidth shouldBe MAX_DIMENSION
        options.outHeight shouldBe MAX_SHORT_EDGE
    }

    @Test
    fun thumbnail_peakMemoryStaysUnderSampledDecodeEnvelope() = runTest {
        // Sampled decode keeps peak heap bounded regardless of source pixel count. A full decode
        // of a 4000×3000 RGBA bitmap would hold ~48 MB; the `inSampleSize`-driven pipeline caps
        // allocations near the sample-sized decode (~1–2 MB for 500×375) plus the exact-resize
        // output (~120 KB for 200×150). The 10 MB envelope below is the DoD ceiling — generous
        // enough to absorb Bitmap.compress's internal buffer + normal GC noise, tight enough to
        // flag a regression that flips the decoder back to full resolution.
        //
        // A concurrent 5 ms sampler runs alongside the thumbnail() call and records the peak heap
        // delta observed across the decode window. A post-execution snapshot would miss the
        // transient 48 MB allocation of a regressed full-resolution decode if ART released the
        // bitmap during the pipeline's internal `finally { bitmap.recycle() }` step before the
        // snapshot. The sampler observes the true peak regardless of GC timing.
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = File(tempDir, "thumb_memory.jpg")

        val runtime = Runtime.getRuntime()
        // Drain soft references / cached allocations so the baseline reflects the quiescent
        // post-fixture heap, not any transient spikes from the PNG writer.
        repeat(GC_ROUNDS) { runtime.gc() }
        val baseline = runtime.totalMemory() - runtime.freeMemory()

        // AtomicLong for cross-dispatcher visibility: the sampler runs on Dispatchers.IO while
        // the test body runs on the test dispatcher. `cancelAndJoin()` establishes the needed
        // happens-before for the final read below.
        val peakUsed = AtomicLong(baseline)
        val samplerScope = CoroutineScope(Dispatchers.IO)
        val sampler = samplerScope.launch {
            while (isActive) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peakUsed.updateAndGet { if (used > it) used else it }
                delay(SAMPLER_INTERVAL_MS)
            }
        }

        val result = try {
            compressor.thumbnail(
                MediaSource.Local.FilePath(input.absolutePath),
                MediaDestination.Local.FilePath(output.absolutePath),
                maxDimension = MAX_DIMENSION,
            )
        } finally {
            sampler.cancelAndJoin()
        }
        // Final snapshot after the sampler has stopped so a regression that spikes immediately
        // before the return still lands in `peakUsed` even if it missed the last 5 ms window.
        val finalUsed = runtime.totalMemory() - runtime.freeMemory()
        peakUsed.updateAndGet { if (finalUsed > it) finalUsed else it }
        val deltaBytes = peakUsed.get() - baseline

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        withClue(
            "Peak heap delta $deltaBytes B exceeds sampled-decode envelope $MAX_PEAK_DELTA_BYTES B — " +
                "possible regression to full-resolution decode",
        ) { deltaBytes shouldBeLessThanOrEqualTo MAX_PEAK_DELTA_BYTES }
    }

    @Test
    fun thumbnail_jpegOutputUsesAtLeastThirtyPercentLessHeapThanWebp() = runTest {
        // CRA-114 regression guard: JPEG output now decodes with `BitmapFactory.Options
        // .inPreferredConfig = RGB_565` (no alpha needed by the JPEG encoder) instead of the
        // default `ARGB_8888`. WEBP output still uses `ARGB_8888` because WEBP can carry alpha.
        // For a 4000×3000 source at maxDim=1000 the sampled-decode bitmap is 1000×750:
        //   ARGB_8888 → 1000 × 750 × 4 ≈ 2.86 MB
        //   RGB_565   → 1000 × 750 × 2 ≈ 1.43 MB
        // Theoretical heap reduction is ~50 %. The DoD asks for ≥ 30 %; that envelope absorbs
        // GC noise, native-side encoder buffers (which `Runtime.totalMemory()` doesn't see anyway
        // since they're on the native heap), and the slight asymmetry between the JPEG and WEBP
        // encoder paths' Java-side buffers. A regression that drops the `inPreferredConfig` hint
        // would push JPEG back up to ~2.86 MB and the assertion would fail loudly.
        //
        // Cross-format comparison (JPEG vs WEBP) is the only way to exercise both decode configs
        // without exposing a test-only seam — `inPreferredConfig` is keyed on the output format
        // by design (`preferredBitmapConfigFor` in AndroidImageCompressor.kt).
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)

        val jpegPeak = measurePeakHeapDelta(input, "thumb_jpeg.jpg", ImageFormat.JPEG)
        val webpPeak = measurePeakHeapDelta(input, "thumb_webp.webp", ImageFormat.WEBP)

        // Guard against a degenerate WEBP measurement (e.g. baseline drift on a noisy emulator
        // pushed the delta near 0). Without this the ratio assertion below would pass vacuously.
        withClue(
            "WEBP peak delta $webpPeak B is implausibly small — the sampler likely missed the " +
                "ARGB_8888 decode bitmap. Expected at least ~$EXPECTED_WEBP_DELTA_FLOOR B for a " +
                "1000×750 ARGB_8888 sampled bitmap.",
        ) { webpPeak shouldBeGreaterThanOrEqualTo EXPECTED_WEBP_DELTA_FLOOR }

        val ratio = jpegPeak.toDouble() / webpPeak.toDouble()
        withClue(
            "JPEG peak heap delta ($jpegPeak B) should be at most ${MAX_JPEG_VS_WEBP_RATIO * 100}% " +
                "of WEBP peak heap delta ($webpPeak B) — observed ratio $ratio. " +
                "A ratio close to 1.0 indicates `inPreferredConfig = RGB_565` is no longer being " +
                "threaded through to the JPEG decode path.",
        ) { ratio shouldBeLessThanOrEqualTo MAX_JPEG_VS_WEBP_RATIO }
    }

    /**
     * Runs `thumbnail()` once for [format] at [LARGE_THUMB_MAX_DIMENSION] and returns the peak
     * Java heap usage delta observed during the call. Mirrors the sampling strategy of
     * [thumbnail_peakMemoryStaysUnderSampledDecodeEnvelope] — concurrent `Dispatchers.IO`
     * sampler at [SAMPLER_INTERVAL_MS] cadence so a transient allocation that would be
     * collected before a post-call snapshot still lands in `peakUsed`.
     */
    private suspend fun measurePeakHeapDelta(
        input: File,
        outputName: String,
        format: ImageFormat,
    ): Long {
        val output = File(tempDir, outputName)
        val runtime = Runtime.getRuntime()
        // Drain prior-run allocations so the baseline reflects only what's resident *now*.
        // Without this, GC of the previous run's decoded bitmap could land mid-sampling and
        // depress the peak delta of the current run.
        repeat(GC_ROUNDS) { runtime.gc() }
        val baseline = runtime.totalMemory() - runtime.freeMemory()

        val peakUsed = AtomicLong(baseline)
        val samplerScope = CoroutineScope(Dispatchers.IO)
        val sampler = samplerScope.launch {
            while (isActive) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peakUsed.updateAndGet { if (used > it) used else it }
                delay(SAMPLER_INTERVAL_MS)
            }
        }

        val result = try {
            compressor.thumbnail(
                MediaSource.Local.FilePath(input.absolutePath),
                MediaDestination.Local.FilePath(output.absolutePath),
                maxDimension = LARGE_THUMB_MAX_DIMENSION,
                format = format,
            )
        } finally {
            sampler.cancelAndJoin()
        }
        val finalUsed = runtime.totalMemory() - runtime.freeMemory()
        peakUsed.updateAndGet { if (finalUsed > it) finalUsed else it }

        withClue("thumbnail($format) failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        return peakUsed.get() - baseline
    }

    @Test
    fun thumbnail_exifRotate90_swapsDimensionsAndClamps() = runTest {
        // Mirror of `AndroidImageCompressorTest.exifOrientation_rotate90_swapsDimensionsAndRotatesPixels`
        // at the thumbnail entry point. With EXIF ORIENTATION = 6 (ROTATE_90 CW) applied to a
        // 200×100 landscape source, the oriented dims are 100×200; at maxDim=200 the long edge
        // is already at the cap so the thumbnail should be exactly 100×200.
        val input = createExifTaggedJpeg(
            tempDir,
            width = EXIF_SRC_WIDTH,
            height = EXIF_SRC_HEIGHT,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90,
        )
        val output = File(tempDir, "thumb_exif90.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        max(options.outWidth, options.outHeight) shouldBeLessThanOrEqualTo MAX_DIMENSION
        // Post-rotation 100×200 — dims swapped relative to the 200×100 source. Proves the
        // sampled-decode path honours EXIF rotation and didn't degrade to "ignore orientation".
        options.outWidth shouldBe EXIF_SRC_HEIGHT
        options.outHeight shouldBe EXIF_SRC_WIDTH
    }

    @Test
    fun thumbnail_zeroMaxDimension_returnsFailureWithoutTouchingSource() = runTest {
        val input = createTestImage(tempDir, 500, 500)
        val output = File(tempDir, "thumb_zero.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = 0,
        )

        withClue("Expected failure for maxDimension=0") { result.isFailure shouldBe true }
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        withClue("No output file should be created for invalid args") { output.exists() shouldBe false }
    }

    @Test
    fun thumbnail_negativeMaxDimension_returnsFailure() = runTest {
        val input = createTestImage(tempDir, 500, 500)
        val output = File(tempDir, "thumb_neg.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = -1,
        )

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        output.exists() shouldBe false
    }

    @Test
    fun thumbnail_sourceAlreadySmaller_producesOutputMatchingSourceDimensions() = runTest {
        // KDoc contract: "never upscales — when maxDimension is larger than both source
        // dimensions the output keeps the source pixel dimensions (re-encoded to format/quality)".
        // Source is 100×100 < maxDimension=200 → output must be 100×100, re-encoded to JPEG.
        val input = createTestImage(tempDir, SMALL_DIM, SMALL_DIM)
        val output = File(tempDir, "thumb_small.jpg")

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
            format = ImageFormat.JPEG,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        OutputValidators.isValidJpeg(output.readBytes()) shouldBe true
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        options.outWidth shouldBe SMALL_DIM
        options.outHeight shouldBe SMALL_DIM
    }

    @Test
    fun thumbnail_bytesInput_exercisesShortCircuitDispatch() = runTest {
        // CRA-95 Stream/Bytes short-circuit: `MediaSource.Local.Bytes` should route through
        // `BitmapFactory.decodeByteArray` without materialising a temp file on the input side.
        // Parallels the iOS Stream/Bytes dispatch test so both platforms exercise the short-
        // circuit end-to-end, not just `MediaSource.Local.FilePath`.
        val srcFile = createTestImage(tempDir, 800, 600)
        val input = MediaSource.Local.Bytes(srcFile.readBytes())
        val output = File(tempDir, "thumb_bytes.jpg")

        val result = compressor.thumbnail(
            input,
            MediaDestination.Local.FilePath(output.absolutePath),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        OutputValidators.isValidJpeg(output.readBytes()) shouldBe true
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)
        max(options.outWidth, options.outHeight) shouldBeLessThanOrEqualTo MAX_DIMENSION
    }

    private companion object {
        const val LARGE_WIDTH = 4_000
        const val LARGE_HEIGHT = 3_000
        const val MAX_DIMENSION = 200
        const val MAX_SHORT_EDGE = 150 // 4:3 source → 150 = 480 * 200/640 — DoD example
        const val EXIF_SRC_WIDTH = 200
        const val EXIF_SRC_HEIGHT = 100
        const val SMALL_DIM = 100
        const val GC_ROUNDS = 3
        // Sampler cadence: 5 ms polls the heap frequently enough to catch a transient
        // full-resolution allocation that would otherwise disappear between snapshots.
        const val SAMPLER_INTERVAL_MS = 5L
        // 10 MB envelope — full 4000×3000 RGBA decode would hit ~48 MB so this is a ~5× safety
        // margin over the expected ~1–2 MB peak of the sampled-decode pipeline.
        const val MAX_PEAK_DELTA_BYTES = 10L * 1024 * 1024

        // CRA-114: maxDim=1000 against 4000×3000 source → sampled bitmap = 1000×750.
        const val LARGE_THUMB_MAX_DIMENSION = 1000

        // Floor for the WEBP (ARGB_8888) peak delta — ~1 MB, well below the theoretical 2.86 MB
        // for a 1000×750 ARGB_8888 bitmap. If the sampler misses the decode bitmap entirely the
        // delta would land near zero; this guards against a vacuously-passing ratio assertion.
        const val EXPECTED_WEBP_DELTA_FLOOR = 1L * 1024 * 1024

        // ≤ 70% means the JPEG decode used at least 30% less heap than the WEBP decode — the DoD
        // threshold from CRA-114. The theoretical floor is ~50% (RGB_565 is 2 B/px vs ARGB_8888's
        // 4 B/px); the 30% margin absorbs encoder-side Java buffers and emulator GC noise.
        const val MAX_JPEG_VS_WEBP_RATIO = 0.70
    }
}
