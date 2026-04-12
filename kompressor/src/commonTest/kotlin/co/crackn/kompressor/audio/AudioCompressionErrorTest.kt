package co.crackn.kompressor.audio

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AudioCompressionErrorTest {

    @Test
    fun unsupportedSourceFormatCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val error = AudioCompressionError.UnsupportedSourceFormat("audio/flac 96kHz 2ch", cause)
        error.details shouldBe "audio/flac 96kHz 2ch"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "audio/flac 96kHz 2ch"
    }

    @Test
    fun subtypesAreDistinguishableViaWhen() {
        val errors = listOf(
            AudioCompressionError.UnsupportedSourceFormat("a"),
            AudioCompressionError.DecodingFailed("b"),
            AudioCompressionError.EncodingFailed("c"),
            AudioCompressionError.IoFailed("d"),
            AudioCompressionError.Unknown("e"),
        )
        errors[0].shouldBeInstanceOf<AudioCompressionError.UnsupportedSourceFormat>()
        errors[1].shouldBeInstanceOf<AudioCompressionError.DecodingFailed>()
        errors[2].shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
        errors[3].shouldBeInstanceOf<AudioCompressionError.IoFailed>()
        errors[4].shouldBeInstanceOf<AudioCompressionError.Unknown>()
    }

    @Test
    fun everySubtypePrefixesItsMessageWithItsCategory() {
        checkNotNull(AudioCompressionError.UnsupportedSourceFormat("x").message) shouldContain "Unsupported"
        checkNotNull(AudioCompressionError.DecodingFailed("x").message) shouldContain "Decoding"
        checkNotNull(AudioCompressionError.EncodingFailed("x").message) shouldContain "Encoding"
        checkNotNull(AudioCompressionError.IoFailed("x").message) shouldContain "IO"
        checkNotNull(AudioCompressionError.Unknown("x").message) shouldContain "Compression failed"
    }
}
