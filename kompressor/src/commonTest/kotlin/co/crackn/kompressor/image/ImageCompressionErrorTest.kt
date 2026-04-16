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
}
