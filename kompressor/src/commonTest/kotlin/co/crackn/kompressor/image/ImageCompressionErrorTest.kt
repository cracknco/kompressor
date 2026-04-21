/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class ImageCompressionErrorTest {

    @Test
    fun unsupportedSourceFormatCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val error = ImageCompressionError.UnsupportedSourceFormat("image/xyz random bytes", cause)
        error.details shouldBe "image/xyz random bytes"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "image/xyz random bytes"
    }

    @Test
    fun decodingFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("truncated")
        val error = ImageCompressionError.DecodingFailed("truncated JPEG at byte 8", cause)
        error.details shouldBe "truncated JPEG at byte 8"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "Decoding failed"
    }

    @Test
    fun subtypesAreDistinguishableViaWhen() {
        val errors = listOf(
            ImageCompressionError.UnsupportedSourceFormat("a"),
            ImageCompressionError.DecodingFailed("b"),
            ImageCompressionError.EncodingFailed("c"),
            ImageCompressionError.IoFailed("d"),
            ImageCompressionError.Unknown("e"),
        )
        errors[0].shouldBeInstanceOf<ImageCompressionError.UnsupportedSourceFormat>()
        errors[1].shouldBeInstanceOf<ImageCompressionError.DecodingFailed>()
        errors[2].shouldBeInstanceOf<ImageCompressionError.EncodingFailed>()
        errors[3].shouldBeInstanceOf<ImageCompressionError.IoFailed>()
        errors[4].shouldBeInstanceOf<ImageCompressionError.Unknown>()
    }

    @Test
    fun everySubtypePrefixesItsMessageWithItsCategory() {
        checkNotNull(ImageCompressionError.UnsupportedSourceFormat("x").message) shouldContain "Unsupported"
        checkNotNull(ImageCompressionError.DecodingFailed("x").message) shouldContain "Decoding"
        checkNotNull(ImageCompressionError.EncodingFailed("x").message) shouldContain "Encoding"
        checkNotNull(ImageCompressionError.IoFailed("x").message) shouldContain "IO"
        checkNotNull(ImageCompressionError.Unknown("x").message) shouldContain "Image compression failed"
    }

    @Test
    fun defaultCauseIsNull() {
        ImageCompressionError.DecodingFailed("x").cause shouldBe null
        ImageCompressionError.Unknown("x").cause shouldBe null
    }

    // ── CRA-90 I/O scaffolding error variants ──────────────────────────────
    //
    // Four new `data class` variants added alongside the existing `class` variants. Shape:
    // `(details: String, override val cause: Throwable? = null)` — the data-class generated
    // `equals` / `hashCode` is what callers rely on (see e.g. error deduplication in
    // upload queues). Each variant has three tests: carries details+cause, extends the
    // parent sealed class, default cause is null.

    @Test
    fun sourceNotFoundCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val err = ImageCompressionError.SourceNotFound("content://x invalid", cause)
        err.details shouldBe "content://x invalid"
        err.cause shouldBe cause
        err shouldBe ImageCompressionError.SourceNotFound("content://x invalid", cause)
        checkNotNull(err.message) shouldContain "Source not found"
    }

    @Test
    fun sourceNotFoundExtendsImageCompressionError() {
        val err: ImageCompressionError = ImageCompressionError.SourceNotFound("x")
        err.shouldBeInstanceOf<ImageCompressionError>()
    }

    @Test
    fun sourceNotFoundCauseDefaultsToNull() {
        ImageCompressionError.SourceNotFound("x").cause shouldBe null
    }

    @Test
    fun sourceReadFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("io")
        val err = ImageCompressionError.SourceReadFailed("offset 512", cause)
        err.details shouldBe "offset 512"
        err.cause shouldBe cause
        err shouldBe ImageCompressionError.SourceReadFailed("offset 512", cause)
        checkNotNull(err.message) shouldContain "Source read failed"
    }

    @Test
    fun sourceReadFailedExtendsImageCompressionError() {
        val err: ImageCompressionError = ImageCompressionError.SourceReadFailed("x")
        err.shouldBeInstanceOf<ImageCompressionError>()
    }

    @Test
    fun sourceReadFailedCauseDefaultsToNull() {
        ImageCompressionError.SourceReadFailed("x").cause shouldBe null
    }

    @Test
    fun destinationWriteFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("ENOSPC")
        val err = ImageCompressionError.DestinationWriteFailed("disk full", cause)
        err.details shouldBe "disk full"
        err.cause shouldBe cause
        err shouldBe ImageCompressionError.DestinationWriteFailed("disk full", cause)
        checkNotNull(err.message) shouldContain "Destination write failed"
    }

    @Test
    fun destinationWriteFailedExtendsImageCompressionError() {
        val err: ImageCompressionError = ImageCompressionError.DestinationWriteFailed("x")
        err.shouldBeInstanceOf<ImageCompressionError>()
    }

    @Test
    fun destinationWriteFailedCauseDefaultsToNull() {
        ImageCompressionError.DestinationWriteFailed("x").cause shouldBe null
    }

    @Test
    fun tempFileFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("cache unavailable")
        val err = ImageCompressionError.TempFileFailed("/cache/tmp", cause)
        err.details shouldBe "/cache/tmp"
        err.cause shouldBe cause
        err shouldBe ImageCompressionError.TempFileFailed("/cache/tmp", cause)
        checkNotNull(err.message) shouldContain "Temp file failed"
    }

    @Test
    fun tempFileFailedExtendsImageCompressionError() {
        val err: ImageCompressionError = ImageCompressionError.TempFileFailed("x")
        err.shouldBeInstanceOf<ImageCompressionError>()
    }

    @Test
    fun tempFileFailedCauseDefaultsToNull() {
        ImageCompressionError.TempFileFailed("x").cause shouldBe null
    }
}
