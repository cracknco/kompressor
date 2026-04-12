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
    fun dECODINGFORMATUNSUPPORTEDMapsToUnsupportedSourceFormat() {
        val mapped = classifyAudioExportErrorCode(
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            "detail FLAC 96kHz",
            null,
        )
        mapped.shouldBeInstanceOf<AudioCompressionError.UnsupportedSourceFormat>()
        checkNotNull(mapped.message) shouldContain "96kHz"
    }

    @Test
    fun dECODERINITFAILEDMapsToUnsupportedSourceFormat() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_DECODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.UnsupportedSourceFormat>()
    }

    @Test
    fun dECODINGFAILEDMapsToDecodingFailed() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_DECODING_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.DecodingFailed>()
    }

    @Test
    fun eNCODERINITFAILEDMapsToEncodingFailed() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun mUXINGFAILEDMapsToEncodingFailed() {
        classifyAudioExportErrorCode(ExportException.ERROR_CODE_MUXING_FAILED, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun misc1xxxErrorFallsThroughToUnknown() {
        classifyAudioExportErrorCode(1999, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.Unknown>()
    }

    @Test
    fun iO2xxxBandMapsToIoFailedViaFallback() {
        // ERROR_CODE_IO_FILE_NOT_FOUND (2005) etc — the 2xxx band classifies as IO.
        classifyAudioExportErrorCode(2005, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.IoFailed>()
    }

    @Test
    fun muxing7xxxBandMapsToEncodingFailedViaFallback() {
        // ERROR_CODE_MUXING_TIMEOUT (7002) — the 7xxx band classifies as encoding.
        classifyAudioExportErrorCode(7002, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun audioProcessing6xxxBandMapsToEncodingFailed() {
        // ERROR_CODE_AUDIO_PROCESSING_FAILED — audio-specific band, covered by the fallback.
        classifyAudioExportErrorCode(6001, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }

    @Test
    fun iOBandLowerBoundary2000MapsToIoFailed() {
        // Pins the band boundary: 2000 is the first IO code (ERROR_CODE_IO_UNSPECIFIED).
        classifyAudioExportErrorCode(2000, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.IoFailed>()
    }

    @Test
    fun encodingBandUpperBoundary4999MapsToEncodingFailed() {
        // Pins the band boundary: 4999 is the highest code still in the encoding band.
        classifyAudioExportErrorCode(4999, "d", null)
            .shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
    }
}
