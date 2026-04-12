package co.crackn.kompressor.video

import androidx.media3.transformer.ExportException
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class Media3ErrorMappingTest {

    @Test
    fun dECODINGFORMATUNSUPPORTEDMapsToUnsupportedSourceFormat() {
        val mapped = classifyExportErrorCode(
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            "detail HEVC 3840x2160",
            null,
        )
        mapped.shouldBeInstanceOf<VideoCompressionError.UnsupportedSourceFormat>()
        checkNotNull(mapped.message) shouldContain "3840x2160"
    }

    @Test
    fun dECODERINITFAILEDMapsToUnsupportedSourceFormat() {
        classifyExportErrorCode(ExportException.ERROR_CODE_DECODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.UnsupportedSourceFormat>()
    }

    @Test
    fun dECODINGFAILEDMapsToDecodingFailed() {
        classifyExportErrorCode(ExportException.ERROR_CODE_DECODING_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.DecodingFailed>()
    }

    @Test
    fun eNCODERINITFAILEDMapsToEncodingFailed() {
        classifyExportErrorCode(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }

    @Test
    fun mUXINGFAILEDMapsToEncodingFailed() {
        classifyExportErrorCode(ExportException.ERROR_CODE_MUXING_FAILED, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }

    @Test
    fun misc1xxxErrorFallsThroughToUnknown() {
        classifyExportErrorCode(1999, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.Unknown>()
    }

    @Test
    fun iO2xxxBandMapsToIoFailedViaFallback() {
        // ERROR_CODE_IO_FILE_NOT_FOUND (2005) etc — the 2xxx band classifies as IO.
        classifyExportErrorCode(2005, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.IoFailed>()
    }

    @Test
    fun muxing7xxxBandMapsToEncodingFailedViaFallback() {
        // ERROR_CODE_MUXING_TIMEOUT (7002) — the 7xxx band classifies as encoding.
        classifyExportErrorCode(7002, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }

    @Test
    fun iOBandLowerBoundary2000MapsToIoFailed() {
        // Pins the band boundary: 2000 is the first IO code (ERROR_CODE_IO_UNSPECIFIED).
        classifyExportErrorCode(2000, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.IoFailed>()
    }

    @Test
    fun encodingBandUpperBoundary4999MapsToEncodingFailed() {
        // Pins the band boundary: 4999 is the highest code still in the encoding band.
        classifyExportErrorCode(4999, "d", null)
            .shouldBeInstanceOf<VideoCompressionError.EncodingFailed>()
    }
}
