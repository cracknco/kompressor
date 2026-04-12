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
    fun `Unknown 2xxx error falls through to Unknown`() {
        classifyExportErrorCode(2001, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.Unknown>()
    }

    @Test
    fun `IO 1xxx band maps to IoFailed via fallback`() {
        // Even if the specific constant isn't in our list, the 1xxx band still classifies as IO.
        classifyExportErrorCode(1999, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.IoFailed>()
    }
}
