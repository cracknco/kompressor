/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.createExifTaggedJpeg
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readBytes
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIImage
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.TASK_VM_INFO_COUNT
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Simulator end-to-end tests for [IosImageCompressor.thumbnail]. Sibling of
 * `ImageThumbnailDeviceTest` on Android — same DoD invariants, exercised through the
 * `CGImageSourceCreateThumbnailAtIndex` sampled-decode path instead of BitmapFactory.
 *
 * Properties verified:
 *  * 4000×3000 PNG → ≤ 200×200 JPEG, 4:3 aspect preserved to the pixel.
 *  * Peak `phys_footprint` delta during sampled decode stays bounded — a regression that flips the
 *    decoder back to full-resolution (~48 MB) would blow past the 10 MB envelope. Sibling of the
 *    Android Java-heap peak guard in [ImageThumbnailDeviceTest].
 *  * `maxDimension ≤ 0` → `Result.failure(IllegalArgumentException)`.
 *  * Source already smaller than `maxDimension` → output keeps source pixel dimensions.
 *  * Fixture with embedded `kCGImagePropertyOrientation = 6` → thumbnail applies the transform
 *    because the iOS impl passes `kCGImageSourceCreateThumbnailWithTransform = true`.
 *  * `MediaSource.Local.Bytes` short-circuit covered alongside `Local.FilePath` so the NSData
 *    fast path in `doThumbnailFromData` is exercised end-to-end.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class ImageThumbnailIosTest {

    private lateinit var testDir: String
    private val compressor = IosImageCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-thumbnail-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun thumbnail_largeSource_clampsLongEdgeToMaxDimension() = runTest {
        val input = createTestImage(testDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = testDir + "thumb_4000x3000.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        withClue("Output should be valid JPEG") { OutputValidators.isValidJpeg(readBytes(output)) shouldBe true }

        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        val w = CGImageGetWidth(cgImage).toInt()
        val h = CGImageGetHeight(cgImage).toInt()
        max(w, h) shouldBeLessThanOrEqualTo MAX_DIMENSION
        // 4000×3000 (4:3) capped at 200 → long edge 200, short edge 150 exactly. CGImageSource's
        // sampled-decode math rounds to integers the same way Android's Bitmap.createScaledBitmap
        // does at this ratio, so dimensions line up byte-for-byte between the two platforms.
        w shouldBe MAX_DIMENSION
        h shouldBe MAX_SHORT_EDGE
    }

    @Test
    fun thumbnail_peakMemoryStaysUnderSampledDecodeEnvelope() = runTest {
        // Sibling of `ImageThumbnailDeviceTest.thumbnail_peakMemoryStaysUnderSampledDecodeEnvelope`
        // on Android. CGImageSource's sampled-decode path keeps process resident memory bounded
        // regardless of source pixel count: a 4000×3000 source decoded at maxDim=200 produces a
        // 200×150 ARGB CGImage (≈ 117 KB) plus encoder + IO scratch buffers. A regression that
        // flips the decoder back to full-resolution (e.g. dropping `kCGImageSourceCreateThumbnail
        // FromImageAlways` or `kCGImageSourceShouldCacheImmediately = false`) would briefly
        // resident the 4000×3000 ARGB bitmap (~48 MB) and blow past the 10 MB envelope.
        //
        // [measurePeakPhysFootprintDelta] runs a concurrent ~5 ms sampler alongside `thumbnail()`
        // and tracks the peak `phys_footprint` delta across the decode window. A post-call
        // snapshot would miss the transient spike if CoreGraphics released the decoded CGImage
        // before the snapshot fired — the sampler observes the true peak regardless of when the
        // autorelease pool drains.
        //
        // `phys_footprint` is what Apple recommends for "RAM the process is using right now":
        // it's the same counter shown in Xcode's memory gauge and used by jetsam telemetry. The
        // DoD names `mach_task_basic_info.phys_footprint`, but `phys_footprint` actually lives on
        // `task_vm_info_data_t` (TASK_VM_INFO flavor) — `mach_task_basic_info` only exposes
        // `resident_size` / `virtual_size`. We use the correct flavor to read the same metric the
        // DoD intends.
        val input = createTestImage(testDir, LARGE_WIDTH, LARGE_HEIGHT)
        val output = testDir + "thumb_memory.jpg"

        val deltaBytes = measurePeakPhysFootprintDelta {
            compressor.thumbnail(
                MediaSource.Local.FilePath(input),
                MediaDestination.Local.FilePath(output),
                maxDimension = MAX_DIMENSION,
            )
        }

        withClue(
            "Peak phys_footprint delta $deltaBytes B exceeds sampled-decode envelope " +
                "$MAX_PEAK_DELTA_BYTES B — possible regression to full-resolution decode",
        ) { deltaBytes shouldBeLessThanOrEqualTo MAX_PEAK_DELTA_BYTES }
    }

    /**
     * Invokes [block] (a `thumbnail()` call) while a concurrent [SAMPLER_INTERVAL_MS]-cadence
     * sampler on `Dispatchers.Default` records the peak `phys_footprint` observed during the
     * call. Returns the delta from the pre-call baseline. Mirrors
     * [ImageThumbnailDeviceTest.measurePeakHeapDelta] on Android — same sampler shape, different
     * underlying counter (process resident memory via Mach `task_info` instead of Java heap usage
     * via `Runtime.totalMemory() - Runtime.freeMemory()`).
     *
     * [AtomicLong] for cross-thread visibility: the sampler runs on a worker thread under
     * `Dispatchers.Default` while the test body runs on the test dispatcher.
     * [kotlinx.coroutines.cancelAndJoin] establishes the happens-before for the final read.
     */
    private suspend fun measurePeakPhysFootprintDelta(
        block: suspend () -> Result<*>,
    ): Long {
        val baseline = physFootprintBytes()
        val peak = AtomicLong(baseline)
        val samplerScope = CoroutineScope(Dispatchers.Default)
        val sampler = samplerScope.launch {
            while (isActive) {
                val now = physFootprintBytes()
                while (true) {
                    val current = peak.load()
                    if (now <= current || peak.compareAndSet(current, now)) break
                }
                delay(SAMPLER_INTERVAL_MS)
            }
        }

        val result = try {
            block()
        } finally {
            sampler.cancelAndJoin()
        }
        // Final snapshot after the sampler stops so a regression that spikes immediately before
        // the return still lands in `peak` even if it missed the last 5 ms window.
        val finalUsed = physFootprintBytes()
        while (true) {
            val current = peak.load()
            if (finalUsed <= current || peak.compareAndSet(current, finalUsed)) break
        }

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        return peak.load() - baseline
    }

    /**
     * Reads the current process `phys_footprint` via `task_info(TASK_VM_INFO, …)`. Returns 0 if
     * the Mach call fails — a 0 reading can never replace an earlier real reading in `peak`'s
     * monotonic-max accumulator, so transient kernel failures don't distort the measurement.
     */
    private fun physFootprintBytes(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<UIntVar>().apply { value = TASK_VM_INFO_COUNT }
        val kr = task_info(
            mach_task_self_,
            TASK_VM_INFO.toUInt(),
            info.ptr.reinterpret(),
            count.ptr,
        )
        if (kr == KERN_SUCCESS) info.phys_footprint.toLong() else 0L
    }

    @Test
    fun thumbnail_exifRotate_swapsOrientedDimensions() = runTest {
        // Fixture is 200×100 (landscape) with `kCGImagePropertyOrientation = 6` ("Right" —
        // visual 90° CW). With `kCGImageSourceCreateThumbnailWithTransform = true`, CGImageSource
        // applies the rotation inside the thumbnail decode — output must be 100×200 (portrait).
        // A regression that drops the transform flag would produce a 200×100 output; a regression
        // that ignores orientation entirely would produce 100×200 but with the wrong pixel content
        // (not checked here — dimension swap is the cheaper canary).
        val input = createExifTaggedJpeg(testDir, EXIF_SRC_WIDTH, EXIF_SRC_HEIGHT, orientation = EXIF_RIGHT)
        val output = testDir + "thumb_exif.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        val w = CGImageGetWidth(cgImage).toInt()
        val h = CGImageGetHeight(cgImage).toInt()
        max(w, h) shouldBeLessThanOrEqualTo MAX_DIMENSION
        // Post-transform dims: 100×200. maxDim=200 caps the long edge but the source is already
        // at 200 on the long edge after orientation is applied, so no downscale fires.
        w shouldBe EXIF_SRC_HEIGHT
        h shouldBe EXIF_SRC_WIDTH
    }

    @Test
    fun thumbnail_zeroMaxDimension_returnsFailureWithoutTouchingSource() = runTest {
        val input = createTestImage(testDir, 500, 500)
        val output = testDir + "thumb_zero.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = 0,
        )

        withClue("Expected failure for maxDimension=0") { result.isFailure shouldBe true }
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        withClue("No output file should be created when args fail validation") {
            NSFileManager.defaultManager.fileExistsAtPath(output) shouldBe false
        }
    }

    @Test
    fun thumbnail_negativeMaxDimension_returnsFailure() = runTest {
        val input = createTestImage(testDir, 500, 500)
        val output = testDir + "thumb_neg.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = -1,
        )

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        NSFileManager.defaultManager.fileExistsAtPath(output) shouldBe false
    }

    @Test
    fun thumbnail_sourceSmallerThanMax_keepsSourceDimensions() = runTest {
        // KDoc contract: "never upscales — when maxDimension is larger than both source dims
        // the output keeps the source pixel dimensions." CGImageSource honours
        // kCGImageSourceThumbnailMaxPixelSize as an upper bound only; it does not scale up.
        val input = createTestImage(testDir, SMALL_DIM, SMALL_DIM)
        val output = testDir + "thumb_small.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        OutputValidators.isValidJpeg(readBytes(output)) shouldBe true
        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        CGImageGetWidth(cgImage).toInt() shouldBe SMALL_DIM
        CGImageGetHeight(cgImage).toInt() shouldBe SMALL_DIM
    }

    @Test
    fun thumbnail_bytesInput_exercisesNsDataShortCircuitDispatch() = runTest {
        // CRA-95 Stream/Bytes short-circuit on iOS: `MediaSource.Local.Bytes` should route
        // through `CGImageSourceCreateWithData` in `doThumbnailFromData` without a temp-file
        // roundtrip. Mirrors the Android Bytes dispatch test so the short-circuit is exercised
        // end-to-end on both platforms, not just `MediaSource.Local.FilePath`.
        val srcPath = createTestImage(testDir, 800, 600)
        val srcBytes = readBytes(srcPath)
        val output = testDir + "thumb_bytes.jpg"

        val result = compressor.thumbnail(
            MediaSource.Local.Bytes(srcBytes),
            MediaDestination.Local.FilePath(output),
            maxDimension = MAX_DIMENSION,
        )

        withClue("thumbnail failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        OutputValidators.isValidJpeg(readBytes(output)) shouldBe true
        val uiImage = UIImage(contentsOfFile = output)
        val cgImage = uiImage.CGImage!!
        max(CGImageGetWidth(cgImage).toInt(), CGImageGetHeight(cgImage).toInt()) shouldBeLessThanOrEqualTo MAX_DIMENSION
    }

    private companion object {
        const val LARGE_WIDTH = 4_000
        const val LARGE_HEIGHT = 3_000
        const val MAX_DIMENSION = 200
        // 4:3 source at long edge 200 → short edge = 200 * 3 / 4 = 150.
        const val MAX_SHORT_EDGE = 150
        const val EXIF_SRC_WIDTH = 200
        const val EXIF_SRC_HEIGHT = 100
        const val EXIF_RIGHT = 6
        const val SMALL_DIM = 100
        // Sampler cadence: 5 ms polls `phys_footprint` frequently enough to catch a transient
        // full-resolution allocation that would otherwise disappear between snapshots. Mirrors
        // the Android sampler's 5 ms interval so a regression on either platform fails the same
        // way.
        const val SAMPLER_INTERVAL_MS = 5L
        // 10 MB envelope — full 4000×3000 ARGB resident decode would hit ~48 MB so this is a ~5×
        // safety margin over the expected ~hundreds-of-KB peak of the sampled-decode pipeline.
        // Kept in lockstep with the Android constant in `ImageThumbnailDeviceTest` so a memory
        // regression on either platform produces the same numeric assertion failure.
        const val MAX_PEAK_DELTA_BYTES = 10L * 1024 * 1024
    }
}
