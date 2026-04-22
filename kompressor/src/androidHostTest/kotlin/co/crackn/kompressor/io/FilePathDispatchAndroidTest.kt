/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test

/**
 * Pins the dispatch side of the new `compress(MediaSource, MediaDestination, ...)` overload on
 * all three Android compressors: Stream / Bytes inputs and Stream outputs short-circuit into
 * `Result.failure(UnsupportedOperationException)` **before** any platform I/O runs. These can
 * therefore live in `androidHostTest` (no emulator) — the error path never constructs a
 * `MediaExtractor`, `Transformer`, or `BitmapFactory`.
 *
 * Bitwise-identical FilePath dispatch is asserted end-to-end in the sibling
 * `androidDeviceTest` / `iosTest` tree because it needs a real input file to round-trip.
 */
class FilePathDispatchAndroidTest {

    @Test
    fun imageCompressorStreamInputFailsWithCra95() = runTest {
        val compressor = AndroidImageCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Stream(Buffer()),
            output = MediaDestination.Local.FilePath("/tmp/unused.jpg"),
            config = ImageCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Stream"
    }

    @Test
    fun imageCompressorBytesInputFailsWithCra95() = runTest {
        val compressor = AndroidImageCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)),
            output = MediaDestination.Local.FilePath("/tmp/unused.jpg"),
            config = ImageCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
        result.exceptionOrNull()!!.message!! shouldContain "MediaSource.Local.Bytes"
    }

    @Test
    fun imageCompressorStreamOutputFailsWithCra95() = runTest {
        val compressor = AndroidImageCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.FilePath("/tmp/unused.jpg"),
            output = MediaDestination.Local.Stream(Buffer()),
            config = ImageCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
        result.exceptionOrNull()!!.message!! shouldContain "MediaDestination.Local.Stream"
    }

    @Test
    fun audioCompressorStreamInputFailsWithCra95() = runTest {
        val compressor = AndroidAudioCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Stream(Buffer()),
            output = MediaDestination.Local.FilePath("/tmp/unused.m4a"),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
        result.exceptionOrNull()!!.message!! shouldContain "CRA-95"
    }

    @Test
    fun audioCompressorBytesInputFailsWithCra95() = runTest {
        val compressor = AndroidAudioCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)),
            output = MediaDestination.Local.FilePath("/tmp/unused.m4a"),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
    }

    @Test
    fun audioCompressorStreamOutputFailsWithCra95() = runTest {
        val compressor = AndroidAudioCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.FilePath("/tmp/unused.mp3"),
            output = MediaDestination.Local.Stream(Buffer()),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
    }

    @Test
    fun videoCompressorStreamInputFailsWithCra95() = runTest {
        val compressor = AndroidVideoCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Stream(Buffer()),
            output = MediaDestination.Local.FilePath("/tmp/unused.mp4"),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
        result.exceptionOrNull()!!.message!! shouldContain "CRA-95"
    }

    @Test
    fun videoCompressorBytesInputFailsWithCra95() = runTest {
        val compressor = AndroidVideoCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)),
            output = MediaDestination.Local.FilePath("/tmp/unused.mp4"),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
    }

    @Test
    fun videoCompressorStreamOutputFailsWithCra95() = runTest {
        val compressor = AndroidVideoCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.FilePath("/tmp/unused.mp4"),
            output = MediaDestination.Local.Stream(Buffer()),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.shouldBeInstanceOf<UnsupportedOperationException>()
    }
}
