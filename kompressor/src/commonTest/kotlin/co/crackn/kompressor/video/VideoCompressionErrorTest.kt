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
}
