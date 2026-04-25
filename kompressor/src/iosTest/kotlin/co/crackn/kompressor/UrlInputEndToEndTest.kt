/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.of
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.assertAvOutputStructurallyEquivalent
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * iOS end-to-end tests for the CRA-94 platform builders — sibling of
 * `androidDeviceTest/ContentUriInputTest`. Asserts that a `file://` [NSURL] flowing through the
 * new `MediaSource.of(url: NSURL)` / `MediaDestination.of(url: NSURL)` builders produces the
 * same compression outcome as the legacy path-based overload, and validates the
 * `MediaSource.of(data: NSData)` image shortcut rounds-trips correctly.
 *
 * **Equivalence model.** For images (JPEG) the outputs are bitwise-identical because
 * `UIImage(data:)` / `UIImage(contentsOfFile:)` share the same Core Graphics decode path
 * and JPEG re-encoding is deterministic. For audio (M4A) and video (MP4) the legacy-vs-novel
 * outputs are byte-identical **within a single wall-clock second**, but AVFoundation stamps
 * `mvhd` / `tkhd` / `mdhd` creation/modification timestamps from `NSDate()` at export start —
 * so two compresses straddling a 1-second boundary flip a handful of byte-level bits in the
 * moov-tail metadata while leaving the file length unchanged. Root-cause investigation
 * (CRA-98, see [UrlInputNonDeterminismInvestigationTest]) confirmed H1 (wall-clock stamping)
 * and refuted H3 (NSURL-path-specific non-determinism): the NSURL dispatch overload is as
 * deterministic as the legacy path per-call; only cross-path timing jitter occasionally pushes
 * the novel compress across a second boundary. The assertion below therefore enforces
 * **sizes equal + differing bytes within the `mvhd`/`tkhd`/`mdhd` timestamp budget** instead
 * of the coarser size-delta tolerance that shipped in PR #143. Stream / Bytes / Stream-output
 * end-to-end coverage (CRA-95) lives in `io.StreamAndBytesEndToEndTest`.
 */
class UrlInputEndToEndTest {

    private lateinit var testDir: String
    private val image = IosImageCompressor()
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-url-e2e-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun image_nsurlInput_producesBitwiseIdenticalOutput() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyPath = testDir + "legacy.jpg"
        val novelPath = testDir + "novel.jpg"

        val legacy = image.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            ImageCompressionConfig(),
        )
        val novel = image.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun audio_nsurlInput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = createTestWav()
        val legacyPath = testDir + "legacy.m4a"
        val novelPath = testDir + "novel.m4a"

        val legacy = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            AudioCompressionConfig(),
        )
        val novel = audio.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertAvOutputStructurallyEquivalent(novelPath, legacyPath)
    }

    @Test
    fun video_nsurlInput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "input.mp4", frameCount = VIDEO_FRAME_COUNT)
        val legacyPath = testDir + "legacy.mp4"
        val novelPath = testDir + "novel.mp4"

        val legacy = video.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            VideoCompressionConfig(),
        )
        val novel = video.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = VideoCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertAvOutputStructurallyEquivalent(novelPath, legacyPath)
    }

    @Test
    fun image_nsdataInput_producesBitwiseIdenticalOutput() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyPath = testDir + "legacy-data.jpg"
        val novelPath = testDir + "novel-data.jpg"

        val inputBytes = readBytes(inputPath)
        val legacy = image.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            ImageCompressionConfig(),
        )
        val novel = image.compress(
            input = MediaSource.of(inputBytes.toNsDataForTest()),
            output = MediaDestination.Local.FilePath(novelPath),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        // UIImage(data:) and UIImage(contentsOfFile:) go through the same CGImageSource
        // decode path, so the encoded output is byte-identical when the JPEG is re-encoded
        // with the same quality + dimensions.
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    /**
     * Pins the **per-second determinism** of AVFoundation exports under the legacy path-based
     * `audio.compress(path, path, config)` entry: two consecutive compresses typically complete
     * in milliseconds and land in the same wall-clock second, so their outputs are byte-identical
     * — but on a heavily loaded `macos-latest` runner the pair can straddle a second tick (the
     * CRA-98 investigation observed exactly this on the NSURL-overload twin in
     * [UrlInputNonDeterminismInvestigationTest], release run 24935723437). The assertion is
     * therefore the same structural-equivalence used elsewhere on this class — sizes MUST
     * match exactly (timestamp drift never resizes the container) and differing bytes MUST
     * fit inside `AV_TIMESTAMP_BYTE_TOLERANCE`.
     *
     * The test still serves as a sentinel for non-determinism *beyond* the AVFoundation
     * wall-clock timestamp budget — any size mismatch or `> AV_TIMESTAMP_BYTE_TOLERANCE`
     * differing bytes between the two consecutive compresses would still fail, catching any
     * real regression in the legacy-path encoder pipeline.
     */
    @Test
    fun audio_twoConsecutiveCompresses_staysWithinTimestampBudget() = runTest {
        val inputPath = createTestWav()
        val firstOut = testDir + "first.m4a"
        val secondOut = testDir + "second.m4a"

        val first = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(firstOut),
            AudioCompressionConfig(),
        )
        val second = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(secondOut),
            AudioCompressionConfig(),
        )

        first.isSuccess shouldBe true
        second.isSuccess shouldBe true

        // Both outputs come from the same legacy path — there is no "novel"/"legacy" role to
        // distinguish, so we use the symmetric `(label, a, b)` overload of
        // assertAvOutputStructurallyEquivalent rather than the role-named one (whose
        // `novel=… legacy=…` failure messages would mislead triage for two-legacy compresses).
        assertAvOutputStructurallyEquivalent(
            label = "audio legacy-twice",
            a = readBytes(firstOut),
            b = readBytes(secondOut),
        )
    }

    private fun createTestWav(): String {
        val bytes = WavGenerator.generateWavBytes(AUDIO_DURATION_S, WAV_SAMPLE_RATE, WAV_CHANNELS)
        val path = testDir + "input.wav"
        writeBytes(path, bytes)
        return path
    }

    private fun ByteArray.toNsDataForTest(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

    private companion object {
        const val IMAGE_SIDE = 512
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
    }
}
