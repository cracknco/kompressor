package co.crackn.kompressor.audio

import androidx.media3.transformer.ExportException
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Mirror of `Media3ErrorMappingTest` — the shared [classifyMedia3ErrorBand] means the video and
 * audio adapters should classify every code identically, but we assert it explicitly so a
 * regression in the audio adapter surfaces without having to wait for a device run.
 */
class AudioMedia3ErrorMappingTest {

    @Test
    fun `DECODING_FORMAT_UNSUPPORTED maps to UnsupportedSourceFormat`() {
        val mapped = classifyAudioExportErrorCode(
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            "detail FLAC 96kHz",
            null,
        )
        mapped.shouldBeInstanceOf<AudioCompressionError.UnsupportedSourceFormat>()
        checkNotNull(mapped.message) shouldContain "96kHz"
    }

    @Test
    fun `DECODER_INIT_FAILED maps to UnsupportedSourceFormat`() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_DECODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.UnsupportedSourceFormat>()
    }

    @Test
    fun `DECODING_FAILED maps to DecodingFailed`() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_DECODING_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.DecodingFailed>()
    }

    @Test
    fun `ENCODER_INIT_FAILED maps to EncodingFailed`() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun `MUXING_FAILED maps to EncodingFailed`() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_MUXING_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun `Misc 1xxx error falls through to Unknown`() {
        classifyAudioExportErrorCode(1999, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.Unknown>()
    }

    @Test
    fun `IO 2xxx band maps to IoFailed via fallback`() {
        // ERROR_CODE_IO_FILE_NOT_FOUND (2005) etc — the 2xxx band classifies as IO.
        classifyAudioExportErrorCode(2005, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.IoFailed>()
    }

    @Test
    fun `Muxing 7xxx band maps to EncodingFailed via fallback`() {
        // ERROR_CODE_MUXING_TIMEOUT (7002) — the 7xxx band classifies as encoding.
        classifyAudioExportErrorCode(7002, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun `Audio processing 6xxx band maps to EncodingFailed`() {
        // ERROR_CODE_AUDIO_PROCESSING_FAILED — audio-specific band, covered by the fallback.
        classifyAudioExportErrorCode(6001, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun `IO band lower boundary 2000 maps to IoFailed`() {
        // Pins the band boundary: 2000 is the first IO code (ERROR_CODE_IO_UNSPECIFIED).
        classifyAudioExportErrorCode(2000, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.IoFailed>()
    }

    @Test
    fun `Encoding band upper boundary 4999 maps to EncodingFailed`() {
        // Pins the band boundary: 4999 is the highest code still in the encoding band.
        classifyAudioExportErrorCode(4999, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }
}
