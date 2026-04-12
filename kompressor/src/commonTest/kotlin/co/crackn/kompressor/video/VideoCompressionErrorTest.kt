package co.crackn.kompressor.video

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class VideoCompressionErrorTest {

    @Test
    fun `UnsupportedSourceFormat carries details and cause`() {
        val cause = RuntimeException("boom")
        val error = VideoCompressionError.UnsupportedSourceFormat("HEVC Main 10, 4K", cause)
        error.details shouldBe "HEVC Main 10, 4K"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "HEVC Main 10, 4K"
    }

    @Test
    fun `subtypes are distinguishable via when`() {
        val errors = listOf(
            VideoCompressionError.UnsupportedSourceFormat("a"),
            VideoCompressionError.DecodingFailed("b"),
            VideoCompressionError.EncodingFailed("c"),
            VideoCompressionError.IoFailed("d"),
            VideoCompressionError.Unknown("e"),
        )
        errors[0].shouldBeInstanceOf<VideoCompressionError.UnsupportedSourceFormat>()
        errors[1].shouldBeInstanceOf<VideoCompressionError.DecodingFailed>()
        errors[2].shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
        errors[3].shouldBeInstanceOf<VideoCompressionError.IoFailed>()
        errors[4].shouldBeInstanceOf<VideoCompressionError.Unknown>()
    }
}
