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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
        // [measurePeakHeapDelta] runs a concurrent 5 ms sampler alongside the thumbnail() call
        // and records the peak heap delta across the decode window. A post-execution snapshot
        // would miss the transient 48 MB allocation of a regressed full-resolution decode if ART
        // released the bitmap during the pipeline's internal `finally { bitmap.recycle() }` step
        // before the snapshot. The sampler observes the true peak regardless of GC timing.
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)

        // Default thumbnail format is JPEG, which now decodes through RGB_565 — the envelope
        // below was originally sized for ARGB_8888 (4 B/px), so it has a comfortable ~10× margin
        // for the post-CRA-114 RGB_565 path (2 B/px) too.
        val deltaBytes = measurePeakHeapDelta(
            input = input,
            outputName = "thumb_memory.jpg",
            format = ImageFormat.JPEG,
            maxDimension = MAX_DIMENSION,
        )

        withClue(
            "Peak heap delta $deltaBytes B exceeds sampled-decode envelope $MAX_PEAK_DELTA_BYTES B — " +
                "possible regression to full-resolution decode",
        ) { deltaBytes shouldBeLessThanOrEqualTo MAX_PEAK_DELTA_BYTES }
    }

    @Test
    fun thumbnail_jpegOutputUsesAtLeastThirtyPercentLessHeapThanWebp() = runTest {
        // CRA-114 regression guard: JPEG thumbnail decode now uses `BitmapFactory.Options
        // .inPreferredConfig = RGB_565` (the JPEG encoder discards alpha and tolerates the
        // narrower colour depth at thumbnail scale) instead of the default `ARGB_8888`. WEBP
        // output still uses `ARGB_8888` because WEBP can carry alpha.
        // For a 4000×3000 source at maxDim=1000 the sampled-decode bitmap is 1000×750:
        //   ARGB_8888 → 1000 × 750 × 4 ≈ 2.86 MB
        //   RGB_565   → 1000 × 750 × 2 ≈ 1.43 MB
        // Theoretical heap reduction is ~50 %. The DoD asks for ≥ 30 %; that envelope absorbs
        // GC noise, native-side encoder buffers (which `Runtime.totalMemory()` doesn't see anyway
        // since they're on the native heap), and the slight asymmetry between the JPEG and WEBP
        // encoder paths' Java-side buffers. A regression that drops the `inPreferredConfig` hint
        // would push JPEG back up to ~2.86 MB and the assertion would fail loudly.
        //
        // **Warmup pass:** before the timed measurements, run a throwaway JPEG and a throwaway
        // WEBP through `thumbnail()` so JIT compilation, class loading, and platform-codec
        // native-lib init costs don't get attributed to the first measured run. Without this,
        // the JPEG (always-first) measurement absorbs ~1-3 MB of one-shot startup heap that
        // shrinks the apparent JPEG-vs-WEBP delta and biases the ratio toward 1.0.
        //
        // Cross-format comparison (JPEG vs WEBP) is the only way to exercise both decode configs
        // without exposing a test-only seam — `inPreferredConfig` is keyed on the output format
        // by design (`preferredBitmapConfigFor` in AndroidImageCompressor.kt) and only invoked
        // from the `thumbnail()` entry point.
        val input = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)

        warmupThumbnailDispatch(input)

        val jpegPeak = measurePeakHeapDelta(
            input = input,
            outputName = "thumb_jpeg.jpg",
            format = ImageFormat.JPEG,
            maxDimension = LARGE_THUMB_MAX_DIMENSION,
        )
        val webpPeak = measurePeakHeapDelta(
            input = input,
            outputName = "thumb_webp.webp",
            format = ImageFormat.WEBP,
            maxDimension = LARGE_THUMB_MAX_DIMENSION,
        )

        // Two-sided floor guard: a sampler run that misses the transient decode bitmap would
        // land near zero on either side. Without floors, ratio = 0 / 0 (NaN, fails the ratio
        // check at least loudly) or ratio = small / large (passes vacuously).
        withClue(
            "JPEG peak delta $jpegPeak B is implausibly small — the sampler likely missed the " +
                "RGB_565 decode bitmap. Expected at least ~$EXPECTED_JPEG_DELTA_FLOOR B for a " +
                "1000×750 RGB_565 sampled bitmap (~1.43 MB theoretical).",
        ) { jpegPeak shouldBeGreaterThanOrEqualTo EXPECTED_JPEG_DELTA_FLOOR }
        withClue(
            "WEBP peak delta $webpPeak B is implausibly small — the sampler likely missed the " +
                "ARGB_8888 decode bitmap. Expected at least ~$EXPECTED_WEBP_DELTA_FLOOR B for a " +
                "1000×750 ARGB_8888 sampled bitmap (~2.86 MB theoretical).",
        ) { webpPeak shouldBeGreaterThanOrEqualTo EXPECTED_WEBP_DELTA_FLOOR }

        val ratio = jpegPeak.toDouble() / webpPeak.toDouble()
        withClue(
            "JPEG peak heap delta ($jpegPeak B) should be at most ${MAX_JPEG_VS_WEBP_RATIO * 100}% " +
                "of WEBP peak heap delta ($webpPeak B) — observed ratio $ratio. " +
                "A ratio close to 1.0 indicates `inPreferredConfig = RGB_565` is no longer being " +
                "threaded through to the JPEG decode path.",
        ) { ratio shouldBeLessThanOrEqualTo MAX_JPEG_VS_WEBP_RATIO }
    }

    @Test
    fun thumbnail_bytesInput_jpegPeakMemoryStaysUnderEnvelope() = runTest {
        // CRA-114 plumbing coverage: the `MediaSource.Local.Bytes` short-circuit routes through
        // `ByteArrayImageSource.decodeSampledBitmap` rather than `FilePathSource`. The
        // `preferredConfig` parameter must reach all three adapters symmetrically, and only the
        // FilePath path was exercised by [thumbnail_jpegOutputUsesAtLeastThirtyPercentLessHeapThanWebp].
        // Asserting the same 10 MB sampled-decode envelope on the Bytes input flushes any future
        // regression where someone adds an adapter without threading `preferredConfig` through.
        val srcFile = createTestImage(tempDir, LARGE_WIDTH, LARGE_HEIGHT)
        val payload = srcFile.readBytes()
        val output = File(tempDir, "thumb_bytes_envelope.jpg")

        val deltaBytes = measurePeakHeapDelta(
            inputProvider = { MediaSource.Local.Bytes(payload) },
            outputPath = output.absolutePath,
            format = ImageFormat.JPEG,
            maxDimension = LARGE_THUMB_MAX_DIMENSION,
        )

        withClue(
            "Bytes-input thumbnail peak heap delta $deltaBytes B exceeds sampled-decode envelope " +
                "$MAX_PEAK_DELTA_BYTES B — possible regression in `ByteArrayImageSource` plumbing.",
        ) { deltaBytes shouldBeLessThanOrEqualTo MAX_PEAK_DELTA_BYTES }
    }

    /**
     * Runs `thumbnail()` once with a [MediaSource.Local.FilePath] input at [maxDimension] and
     * returns the peak Java heap usage delta observed during the call. Thin wrapper over the
     * provider-form [measurePeakHeapDelta] for the FilePath case.
     */
    private suspend fun measurePeakHeapDelta(
        input: File,
        outputName: String,
        format: ImageFormat,
        maxDimension: Int,
    ): Long = measurePeakHeapDelta(
        inputProvider = { MediaSource.Local.FilePath(input.absolutePath) },
        outputPath = File(tempDir, outputName).absolutePath,
        format = format,
        maxDimension = maxDimension,
    )

    /**
     * Runs `thumbnail()` once with the [inputProvider]'s [MediaSource] at [maxDimension] and
     * returns the peak Java heap usage delta observed across the call. A `coroutineScope`-bound
     * `Dispatchers.IO` sampler at [SAMPLER_INTERVAL_MS] cadence catches transient allocations
     * that a post-call snapshot would miss. Used by both the absolute-envelope checks
     * ([thumbnail_peakMemoryStaysUnderSampledDecodeEnvelope] /
     * [thumbnail_bytesInput_jpegPeakMemoryStaysUnderEnvelope]) and the cross-format ratio check
     * ([thumbnail_jpegOutputUsesAtLeastThirtyPercentLessHeapThanWebp]).
     *
     * `inputProvider` is a lambda rather than a `MediaSource` value so the sampler can observe
     * the allocation of the source itself when relevant (e.g. `Bytes` payload retention) — the
     * provider is invoked *after* the baseline snapshot, *before* the `thumbnail()` call.
     *
     * `AtomicLong` for cross-dispatcher visibility: the sampler runs on `Dispatchers.IO` while
     * the test body runs on the test dispatcher. `cancelAndJoin()` establishes the
     * happens-before for the final read.
     */
    private suspend fun measurePeakHeapDelta(
        inputProvider: () -> MediaSource,
        outputPath: String,
        format: ImageFormat,
        maxDimension: Int,
    ): Long = coroutineScope {
        val runtime = Runtime.getRuntime()
        val baseline = drainGcAndSnapshot(runtime)
        val peakUsed = AtomicLong(baseline)
        val sampler = startHeapSampler(this, runtime, peakUsed)
        val result = try {
            compressor.thumbnail(
                inputProvider(),
                MediaDestination.Local.FilePath(outputPath),
                maxDimension = maxDimension,
                format = format,
            )
        } finally {
            sampler.cancelAndJoin()
        }
        // Post-sampler snapshot so a regression that spikes between the last 5 ms tick and
        // the cancellation still lands in `peakUsed`.
        val finalUsed = runtime.totalMemory() - runtime.freeMemory()
        peakUsed.updateAndGet { if (finalUsed > it) finalUsed else it }
        withClue("thumbnail($format) failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        peakUsed.get() - baseline
    }

    /**
     * Drain prior-run allocations and capture a heap baseline. `runtime.gc()` is best-effort
     * — the JVM spec does not guarantee it triggers a collection — but ART honours the request
     * in practice, and three consecutive passes plus the [GC_ROUNDS] hint give a stable enough
     * baseline that residue from earlier tests doesn't shift the per-test peak delta.
     */
    private fun drainGcAndSnapshot(runtime: Runtime): Long {
        repeat(GC_ROUNDS) { runtime.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Launches the heap-peak sampler under [scope] and returns its [Job] so callers cancel it. */
    private fun startHeapSampler(
        scope: CoroutineScope,
        runtime: Runtime,
        peakUsed: AtomicLong,
    ): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val used = runtime.totalMemory() - runtime.freeMemory()
            peakUsed.updateAndGet { if (used > it) used else it }
            delay(SAMPLER_INTERVAL_MS)
        }
    }

    /**
     * Burns one JPEG and one WEBP `thumbnail()` against [input] before any timed measurement,
     * so JIT compilation, class loading, and `BitmapFactory` / `Bitmap.compress` native-lib
     * init costs are amortised before the first sampled run. Without this, the always-first
     * JPEG measurement inherits ~1-3 MB of one-shot startup heap and shrinks the apparent
     * JPEG-vs-WEBP ratio toward 1.0.
     */
    private suspend fun warmupThumbnailDispatch(input: File) {
        compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(File(tempDir, "warmup_jpeg.jpg").absolutePath),
            maxDimension = LARGE_THUMB_MAX_DIMENSION,
            format = ImageFormat.JPEG,
        )
        compressor.thumbnail(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(File(tempDir, "warmup_webp.webp").absolutePath),
            maxDimension = LARGE_THUMB_MAX_DIMENSION,
            format = ImageFormat.WEBP,
        )
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

        // Floor for the JPEG (RGB_565) peak delta — ~0.5 MB, ~35 % of the theoretical 1.43 MB
        // for a 1000×750 RGB_565 bitmap (matches the 35 % margin the WEBP floor uses against
        // 2.86 MB). Without this floor a sampler that missed *both* decodes would yield a tiny
        // jpegPeak / non-zero webpPeak ratio that passes vacuously.
        const val EXPECTED_JPEG_DELTA_FLOOR = 512L * 1024

        // ≤ 70% means the JPEG decode used at least 30% less heap than the WEBP decode — the DoD
        // threshold from CRA-114. The theoretical floor is ~50% (RGB_565 is 2 B/px vs ARGB_8888's
        // 4 B/px); the 30% margin absorbs encoder-side Java buffers and emulator GC noise.
        const val MAX_JPEG_VS_WEBP_RATIO = 0.70
    }
}
