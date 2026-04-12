package co.crackn.kompressor.video

import androidx.media3.transformer.ExportException
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class Media3ErrorMappingTest {

    @Test
    fun `DECODING_FORMAT_UNSUPPORTED maps to UnsupportedSourceFormat`() {
        val mapped = classifyExportErrorCode(
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            "detail HEVC 3840x2160",
            null,
        )
        mapped.shouldBeInstanceOf<VideoCompressionError.UnsupportedSourceFormat>()
        checkNotNull(mapped.message) shouldContain "3840x2160"
    }

    @Test
    fun `DECODER_INIT_FAILED maps to UnsupportedSourceFormat`() {
        classifyExportErrorCode(ExportException.ERROR_CODE_DECODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.UnsupportedSourceFormat>()
    }

    @Test
    fun `DECODING_FAILED maps to DecodingFailed`() {
        classifyExportErrorCode(ExportException.ERROR_CODE_DECODING_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.DecodingFailed>()
    }

    @Test
    fun `ENCODER_INIT_FAILED maps to EncodingFailed`() {
        classifyExportErrorCode(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }

    @Test
    fun `MUXING_FAILED maps to EncodingFailed`() {
        classifyExportErrorCode(ExportException.ERROR_CODE_MUXING_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }

    @Test
    fun `Misc 1xxx error falls through to Unknown`() {
        classifyExportErrorCode(1999, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.Unknown>()
    }

    @Test
    fun `IO 2xxx band maps to IoFailed via fallback`() {
        // ERROR_CODE_IO_FILE_NOT_FOUND (2005) etc — the 2xxx band classifies as IO.
        classifyExportErrorCode(2005, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.IoFailed>()
    }

    @Test
    fun `Muxing 7xxx band maps to EncodingFailed via fallback`() {
        // ERROR_CODE_MUXING_TIMEOUT (7002) — the 7xxx band classifies as encoding.
        classifyExportErrorCode(7002, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }
}
