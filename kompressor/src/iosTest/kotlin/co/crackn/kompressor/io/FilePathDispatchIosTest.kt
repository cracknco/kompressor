/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okio.Buffer

/**
 * iOS sibling of [FilePathDispatchAndroidTest] — pins that the dispatch side of the new
 * `compress(MediaSource, MediaDestination, ...)` overload short-circuits into
 * `Result.failure(UnsupportedOperationException)` **before** any platform I/O runs on each
 * of the three iOS compressors. These can therefore live in `iosTest` without needing a
 * real input file: the error path never opens an `AVURLAsset`, `AVAssetExportSession`, or
 * `UIImage`.
 *
 * Bitwise-identical FilePath dispatch is asserted end-to-end in
 * [FilePathEndToEndTest][co.crackn.kompressor.FilePathEndToEndTest] because it needs a
 * real input file to round-trip.
 */
class FilePathDispatchIosTest {

    @Test
    fun imageCompressorStreamInputFailsWithCra95() = runTest {
        val compressor = IosImageCompressor()

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
        val compressor = IosImageCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)),
            output = MediaDestination.Local.FilePath("/tmp/unused.jpg"),
            config = ImageCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Bytes"
    }

    @Test
    fun imageCompressorStreamOutputFailsWithCra95() = runTest {
        val compressor = IosImageCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.FilePath("/tmp/unused.jpg"),
            output = MediaDestination.Local.Stream(Buffer()),
            config = ImageCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaDestination.Local.Stream"
    }

    @Test
    fun audioCompressorStreamInputFailsWithCra95() = runTest {
        val compressor = IosAudioCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Stream(Buffer()),
            output = MediaDestination.Local.FilePath("/tmp/unused.m4a"),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Stream"
    }

    @Test
    fun audioCompressorBytesInputFailsWithCra95() = runTest {
        val compressor = IosAudioCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)),
            output = MediaDestination.Local.FilePath("/tmp/unused.m4a"),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Bytes"
    }

    @Test
    fun audioCompressorStreamOutputFailsWithCra95() = runTest {
        val compressor = IosAudioCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.FilePath("/tmp/unused.mp3"),
            output = MediaDestination.Local.Stream(Buffer()),
            config = AudioCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaDestination.Local.Stream"
    }

    @Test
    fun videoCompressorStreamInputFailsWithCra95() = runTest {
        val compressor = IosVideoCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Stream(Buffer()),
            output = MediaDestination.Local.FilePath("/tmp/unused.mp4"),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Stream"
    }

    @Test
    fun videoCompressorBytesInputFailsWithCra95() = runTest {
        val compressor = IosVideoCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)),
            output = MediaDestination.Local.FilePath("/tmp/unused.mp4"),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Bytes"
    }

    @Test
    fun videoCompressorStreamOutputFailsWithCra95() = runTest {
        val compressor = IosVideoCompressor()

        val result = compressor.compress(
            input = MediaSource.Local.FilePath("/tmp/unused.mp4"),
            output = MediaDestination.Local.Stream(Buffer()),
            config = VideoCompressionConfig(),
        )

        result.isFailure shouldBe true
        val e = result.exceptionOrNull()!!
        e.shouldBeInstanceOf<UnsupportedOperationException>()
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaDestination.Local.Stream"
    }
}
