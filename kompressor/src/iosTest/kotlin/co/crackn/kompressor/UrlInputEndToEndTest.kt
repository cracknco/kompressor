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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * new `MediaSource.of(url: NSURL)` / `MediaDestination.of(url: NSURL)` builders produces
 * bitwise-identical output to the legacy path-based overload; validates the
 * `MediaSource.of(data: NSData)` image shortcut rounds-trips correctly; and pins
 * `UnsupportedOperationException` for audio / video NSData input (scoped to CRA-95).
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
    fun audio_nsurlInput_producesBitwiseIdenticalOutput() = runTest {
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
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun video_nsurlInput_producesBitwiseIdenticalOutput() = runTest {
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
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
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

    @Test
    fun audio_nsdataInput_failsWithCra95Message() = runTest {
        val dummyOutput = testDir + "unused.m4a"

        val result = audio.compress(
            input = MediaSource.of(byteArrayOf(0, 1, 2).toNsDataForTest()),
            output = MediaDestination.Local.FilePath(dummyOutput),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "NSData"
    }

    @Test
    fun video_nsdataInput_failsWithCra95Message() = runTest {
        val dummyOutput = testDir + "unused.mp4"

        val result = video.compress(
            input = MediaSource.of(byteArrayOf(0, 1, 2).toNsDataForTest()),
            output = MediaDestination.Local.FilePath(dummyOutput),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "NSData"
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
