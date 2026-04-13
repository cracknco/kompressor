@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.AVNSErrorException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSPOSIXErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the iOS [mapToAudioError] adapter classifies common AVFoundation + POSIX errors
 * into the right typed [AudioCompressionError] subtype.
 */
class IosAudioErrorMappingTest {

    private fun avError(code: Int): AVNSErrorException {
        val ns = NSError.errorWithDomain(
            domain = AVFoundationErrorDomain,
            code = code.toLong(),
            userInfo = null,
        )
        return AVNSErrorException(ns, "test")
    }

    private fun posixError(code: Int): AVNSErrorException {
        val ns = NSError.errorWithDomain(
            domain = NSPOSIXErrorDomain,
            code = code.toLong(),
            userInfo = null,
        )
        return AVNSErrorException(ns, "test")
    }

    @Test
    fun fileFormatNotRecognized_mapsToUnsupportedSourceFormat() {
        val ex = avError(-11828)
        val err = mapToAudioError(ex)
        assertTrue(err is AudioCompressionError.UnsupportedSourceFormat, "got $err")
        assertSame(ex, err.cause)
    }

    @Test
    fun fileFailedToParse_mapsToUnsupportedSourceFormat() {
        val err = mapToAudioError(avError(-11829))
        assertTrue(err is AudioCompressionError.UnsupportedSourceFormat, "got $err")
    }

    @Test
    fun mediaDataNotFound_mapsToUnsupportedSourceFormat() {
        val err = mapToAudioError(avError(-11831))
        assertTrue(err is AudioCompressionError.UnsupportedSourceFormat, "got $err")
    }

    @Test
    fun unknownAvCode_mapsToDecodingFailed() {
        val err = mapToAudioError(avError(-11800))
        assertTrue(err is AudioCompressionError.DecodingFailed, "got $err")
    }

    @Test
    fun mediaDataInWrongFormat_mapsToDecodingFailed() {
        val err = mapToAudioError(avError(-11832))
        assertTrue(err is AudioCompressionError.DecodingFailed, "got $err")
    }

    @Test
    fun noDataCaptured_mapsToDecodingFailed() {
        val err = mapToAudioError(avError(-11841))
        assertTrue(err is AudioCompressionError.DecodingFailed, "got $err")
    }

    @Test
    fun diskFull_mapsToIoFailed() {
        val err = mapToAudioError(avError(-11823))
        assertTrue(err is AudioCompressionError.IoFailed, "got $err")
    }

    @Test
    fun sessionNotRunning_mapsToIoFailed() {
        val err = mapToAudioError(avError(-11824))
        assertTrue(err is AudioCompressionError.IoFailed, "got $err")
    }

    @Test
    fun exportFailed_mapsToEncodingFailed() {
        val err = mapToAudioError(avError(-11820))
        assertTrue(err is AudioCompressionError.EncodingFailed, "got $err")
    }

    @Test
    fun outOfMemory_mapsToEncodingFailed() {
        val err = mapToAudioError(avError(-11821))
        assertTrue(err is AudioCompressionError.EncodingFailed, "got $err")
    }

    @Test
    fun posixEacces_mapsToIoFailed() {
        val err = mapToAudioError(posixError(13))
        assertTrue(err is AudioCompressionError.IoFailed, "got $err")
    }

    @Test
    fun posixEnospc_mapsToIoFailed() {
        val err = mapToAudioError(posixError(28))
        assertTrue(err is AudioCompressionError.IoFailed, "got $err")
    }

    @Test
    fun posixEnoent_mapsToIoFailed() {
        val err = mapToAudioError(posixError(2))
        assertTrue(err is AudioCompressionError.IoFailed, "got $err")
    }

    @Test
    fun posixErofs_mapsToIoFailed() {
        val err = mapToAudioError(posixError(30))
        assertTrue(err is AudioCompressionError.IoFailed, "got $err")
    }

    @Test
    fun unknownDomain_fallsBackToUnknown() {
        val ns = NSError.errorWithDomain(domain = "co.test.unknown", code = 42L, userInfo = null)
        val err = mapToAudioError(AVNSErrorException(ns, "test"))
        assertTrue(err is AudioCompressionError.Unknown, "got $err")
    }

    @Test
    fun nonNsErrorThrowable_fallsBackToUnknown() {
        val cause = IllegalStateException("boom")
        val err = mapToAudioError(cause)
        assertTrue(err is AudioCompressionError.Unknown, "got $err")
        assertEquals(cause, err.cause)
    }

    @Test
    fun unsupportedConfiguration_passesThrough() {
        val typed = AudioCompressionError.UnsupportedConfiguration("nope")
        val err = mapToAudioError(typed)
        assertSame(typed, err)
    }
}
