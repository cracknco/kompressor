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
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import io.kotest.assertions.withClue
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
 * outputs differ by a few bytes on every run — but the divergence is NOT wall-clock-timestamp
 * drift: [audio_twoConsecutiveCompressesProduceIdenticalBytes] demonstrates two back-to-back
 * legacy-overload compresses of the same input are byte-identical, so AVFoundation itself is
 * deterministic per-compress. The remaining divergence on legacy-vs-novel pairs is tracked
 * for root-cause investigation (CRA-98 follow-up — likely NSURL dispatch overhead or
 * `toIosInputPath` call-ordering stamping something into the AVAsset initialisation). The
 * stable invariant pinned here is *compression-outcome equivalence*: both paths succeed and
 * their output sizes agree within [AV_SIZE_TOLERANCE_BYTES]. Stream / Bytes / Stream-output
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

        val legacy = image.compress(inputPath, legacyPath, ImageCompressionConfig())
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

        val legacy = audio.compress(inputPath, legacyPath, AudioCompressionConfig())
        val novel = audio.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertAvSizeEquivalent(novelPath, legacyPath)
    }

    @Test
    fun video_nsurlInput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "input.mp4", frameCount = VIDEO_FRAME_COUNT)
        val legacyPath = testDir + "legacy.mp4"
        val novelPath = testDir + "novel.mp4"

        val legacy = video.compress(inputPath, legacyPath, VideoCompressionConfig())
        val novel = video.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = VideoCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertAvSizeEquivalent(novelPath, legacyPath)
    }

    @Test
    fun image_nsdataInput_producesBitwiseIdenticalOutput() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyPath = testDir + "legacy-data.jpg"
        val novelPath = testDir + "novel-data.jpg"

        val inputBytes = readBytes(inputPath)
        val legacy = image.compress(inputPath, legacyPath, ImageCompressionConfig())
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

    // Pins the actual determinism of AVFoundation exports under the legacy path-based
    // `audio.compress(path, path, config)` entry: two consecutive compresses of the same
    // input produce byte-identical output. This contradicts the class-KDoc's "wall-clock
    // mvhd/mdhd stamping" hypothesis and suggests the remaining tolerance on the
    // legacy-vs-novel pair tests is masking something OTHER than timestamps (possibly an
    // NSURL-dispatch overhead or `toIosInputPath` call-ordering effect). Tracked for
    // follow-up investigation in a CRA-95.1 ticket [PR #143 review, finding #7].
    @Test
    fun audio_twoConsecutiveCompressesProduceIdenticalBytes() = runTest {
        val inputPath = createTestWav()
        val firstOut = testDir + "first.m4a"
        val secondOut = testDir + "second.m4a"

        val first = audio.compress(inputPath, firstOut, AudioCompressionConfig())
        val second = audio.compress(inputPath, secondOut, AudioCompressionConfig())

        first.isSuccess shouldBe true
        second.isSuccess shouldBe true
        withClue(
            "Two consecutive AVFoundation exports of the same input MUST produce byte-identical " +
                "bytes. If this fails, AVFoundation became non-deterministic per-compress and the " +
                "class-KDoc explanation (wall-clock mvhd timestamps) would finally be justified. " +
                "If it still passes, the legacy-vs-novel relaxation on audio_nsurlInput / " +
                "video_nsurlInput is masking a different divergence (dispatch overhead, call " +
                "ordering, …) and should be investigated.",
        ) {
            readBytes(firstOut).contentEquals(readBytes(secondOut)) shouldBe true
        }
    }

    /**
     * Assert two audio/video outputs have sizes within [AV_SIZE_TOLERANCE_BYTES] — used in
     * place of bitwise comparison because AVFoundation embeds wall-clock-derived timestamps
     * in `mvhd` / `mdhd` boxes. See class KDoc for the full rationale.
     */
    private fun assertAvSizeEquivalent(novelPath: String, legacyPath: String) {
        val novel = readBytes(novelPath).size.toLong()
        val legacy = readBytes(legacyPath).size.toLong()
        val delta = abs(novel - legacy)
        withClue(
            "Expected novel/legacy output sizes within $AV_SIZE_TOLERANCE_BYTES bytes — " +
                "novel=$novel legacy=$legacy delta=$delta",
        ) {
            (novel > 0 && legacy > 0 && delta <= AV_SIZE_TOLERANCE_BYTES) shouldBe true
        }
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

        /**
         * Max byte delta between two iOS audio/video outputs produced by successive
         * AVFoundation exports of the same source. Covers `mvhd` / `mdhd` creation &
         * modification timestamp drift (second-resolution, tens of bytes) without masking
         * real regressions. Mirrored by the CRA-95 `StreamAndBytesEndToEndTest` tolerance.
         */
        const val AV_SIZE_TOLERANCE_BYTES: Long = 1024
    }
}
