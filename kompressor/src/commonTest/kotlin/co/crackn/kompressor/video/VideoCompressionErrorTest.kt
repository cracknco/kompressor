/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class VideoCompressionErrorTest {

    @Test
    fun unsupportedSourceFormatCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val error = VideoCompressionError.UnsupportedSourceFormat("HEVC Main 10, 4K", cause)
        error.details shouldBe "HEVC Main 10, 4K"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "HEVC Main 10, 4K"
    }

    @Test
    fun subtypesAreDistinguishableViaWhen() {
        val errors = listOf(
            VideoCompressionError.UnsupportedSourceFormat("a"),
            VideoCompressionError.DecodingFailed("b"),
            VideoCompressionError.EncodingFailed("c"),
            VideoCompressionError.IoFailed("d"),
            VideoCompressionError.Unknown("e"),
            VideoCompressionError.Hdr10NotSupported(device = "Pixel 6", codec = "c2.qti.hevc.encoder", reason = "init"),
        )
        errors[0].shouldBeInstanceOf<VideoCompressionError.UnsupportedSourceFormat>()
        errors[1].shouldBeInstanceOf<VideoCompressionError.DecodingFailed>()
        errors[2].shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
        errors[3].shouldBeInstanceOf<VideoCompressionError.IoFailed>()
        errors[4].shouldBeInstanceOf<VideoCompressionError.Unknown>()
        errors[5].shouldBeInstanceOf<VideoCompressionError.Hdr10NotSupported>()
    }

    @Test
    fun hdr10NotSupportedCarriesDeviceCodecAndReason() {
        val cause = RuntimeException("MediaCodec.configure() threw MediaCodec.CodecException")
        val error = VideoCompressionError.Hdr10NotSupported(
            device = "Pixel 6",
            codec = "c2.qti.hevc.encoder",
            reason = "encoder advertised Main10 but configure() threw",
            cause = cause,
        )
        error.device shouldBe "Pixel 6"
        error.codec shouldBe "c2.qti.hevc.encoder"
        error.reason shouldContain "Main10"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "Pixel 6"
        checkNotNull(error.message) shouldContain "c2.qti.hevc.encoder"
    }

    // ── CRA-90 I/O scaffolding error variants ──────────────────────────────
    //
    // Four new `data class` variants added alongside the existing `class` variants. Shape:
    // `(details: String, override val cause: Throwable? = null)` — the data-class generated
    // `equals` / `hashCode` is what callers rely on. Each variant has three tests: carries
    // details+cause, extends the parent sealed class, default cause is null.

    @Test
    fun sourceNotFoundCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val err = VideoCompressionError.SourceNotFound("content://x invalid", cause)
        err.details shouldBe "content://x invalid"
        err.cause shouldBe cause
        err shouldBe VideoCompressionError.SourceNotFound("content://x invalid", cause)
        checkNotNull(err.message) shouldContain "Source not found"
    }

    @Test
    fun sourceNotFoundExtendsVideoCompressionError() {
        val err: VideoCompressionError = VideoCompressionError.SourceNotFound("x")
        err.shouldBeInstanceOf<VideoCompressionError>()
    }

    @Test
    fun sourceNotFoundCauseDefaultsToNull() {
        VideoCompressionError.SourceNotFound("x").cause shouldBe null
    }

    @Test
    fun sourceReadFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("io")
        val err = VideoCompressionError.SourceReadFailed("offset 512", cause)
        err.details shouldBe "offset 512"
        err.cause shouldBe cause
        err shouldBe VideoCompressionError.SourceReadFailed("offset 512", cause)
        checkNotNull(err.message) shouldContain "Source read failed"
    }

    @Test
    fun sourceReadFailedExtendsVideoCompressionError() {
        val err: VideoCompressionError = VideoCompressionError.SourceReadFailed("x")
        err.shouldBeInstanceOf<VideoCompressionError>()
    }

    @Test
    fun sourceReadFailedCauseDefaultsToNull() {
        VideoCompressionError.SourceReadFailed("x").cause shouldBe null
    }

    @Test
    fun destinationWriteFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("ENOSPC")
        val err = VideoCompressionError.DestinationWriteFailed("disk full", cause)
        err.details shouldBe "disk full"
        err.cause shouldBe cause
        err shouldBe VideoCompressionError.DestinationWriteFailed("disk full", cause)
        checkNotNull(err.message) shouldContain "Destination write failed"
    }

    @Test
    fun destinationWriteFailedExtendsVideoCompressionError() {
        val err: VideoCompressionError = VideoCompressionError.DestinationWriteFailed("x")
        err.shouldBeInstanceOf<VideoCompressionError>()
    }

    @Test
    fun destinationWriteFailedCauseDefaultsToNull() {
        VideoCompressionError.DestinationWriteFailed("x").cause shouldBe null
    }

    @Test
    fun tempFileFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("cache unavailable")
        val err = VideoCompressionError.TempFileFailed("/cache/tmp", cause)
        err.details shouldBe "/cache/tmp"
        err.cause shouldBe cause
        err shouldBe VideoCompressionError.TempFileFailed("/cache/tmp", cause)
        checkNotNull(err.message) shouldContain "Temp file failed"
    }

    @Test
    fun tempFileFailedExtendsVideoCompressionError() {
        val err: VideoCompressionError = VideoCompressionError.TempFileFailed("x")
        err.shouldBeInstanceOf<VideoCompressionError>()
    }

    @Test
    fun tempFileFailedCauseDefaultsToNull() {
        VideoCompressionError.TempFileFailed("x").cause shouldBe null
    }

    // ── CRA-109 M12 T2: thumbnail() timestamp-out-of-range variant ────────
    //
    // Same shape as the CRA-90 I/O variants: data class, `details + cause = null` defaults,
    // message-contains-details, extends the sealed parent. Grouped in a block of three so a
    // regression (rename, deleted default, moved message prefix) fails loudly.

    @Test
    fun timestampOutOfRangeCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val err = VideoCompressionError.TimestampOutOfRange("atMillis=5000 > duration=3000", cause)
        err.details shouldBe "atMillis=5000 > duration=3000"
        err.cause shouldBe cause
        err shouldBe VideoCompressionError.TimestampOutOfRange("atMillis=5000 > duration=3000", cause)
        checkNotNull(err.message) shouldContain "Timestamp out of range"
        checkNotNull(err.message) shouldContain "atMillis=5000"
    }

    @Test
    fun timestampOutOfRangeExtendsVideoCompressionError() {
        val err: VideoCompressionError = VideoCompressionError.TimestampOutOfRange("x")
        err.shouldBeInstanceOf<VideoCompressionError>()
    }

    @Test
    fun timestampOutOfRangeCauseDefaultsToNull() {
        VideoCompressionError.TimestampOutOfRange("x").cause shouldBe null
    }
}
