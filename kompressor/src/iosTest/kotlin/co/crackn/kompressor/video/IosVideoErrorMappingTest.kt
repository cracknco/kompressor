@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.video

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
 * Verifies the iOS [mapToVideoError] adapter classifies common AVFoundation + POSIX errors
 * into the right typed [VideoCompressionError] subtype. Mirrors the Android Media3 mapping
 * coverage in `androidHostTest/.../Media3ErrorMappingTest.kt`.
 */
class IosVideoErrorMappingTest {

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
        val err = mapToVideoError(ex)
        assertTrue(err is VideoCompressionError.UnsupportedSourceFormat, "got $err")
        assertSame(ex, err.cause)
    }

    @Test
    fun fileFailedToParse_mapsToUnsupportedSourceFormat() {
        val err = mapToVideoError(avError(-11829))
        assertTrue(err is VideoCompressionError.UnsupportedSourceFormat, "got $err")
    }

    @Test
    fun mediaDataNotFound_mapsToUnsupportedSourceFormat() {
        val err = mapToVideoError(avError(-11831))
        assertTrue(err is VideoCompressionError.UnsupportedSourceFormat, "got $err")
    }

    @Test
    fun unknownAvCode_mapsToDecodingFailed() {
        val err = mapToVideoError(avError(-11800))
        assertTrue(err is VideoCompressionError.DecodingFailed, "got $err")
    }

    @Test
    fun mediaDataInWrongFormat_mapsToDecodingFailed() {
        val err = mapToVideoError(avError(-11832))
        assertTrue(err is VideoCompressionError.DecodingFailed, "got $err")
    }

    @Test
    fun noDataCaptured_mapsToDecodingFailed() {
        val err = mapToVideoError(avError(-11841))
        assertTrue(err is VideoCompressionError.DecodingFailed, "got $err")
    }

    @Test
    fun diskFull_mapsToIoFailed() {
        val err = mapToVideoError(avError(-11823))
        assertTrue(err is VideoCompressionError.IoFailed, "got $err")
    }

    @Test
    fun sessionNotRunning_mapsToIoFailed() {
        val err = mapToVideoError(avError(-11824))
        assertTrue(err is VideoCompressionError.IoFailed, "got $err")
    }

    @Test
    fun exportFailed_mapsToEncodingFailed() {
        val err = mapToVideoError(avError(-11820))
        assertTrue(err is VideoCompressionError.EncodingFailed, "got $err")
    }

    @Test
    fun outOfMemory_mapsToEncodingFailed() {
        val err = mapToVideoError(avError(-11821))
        assertTrue(err is VideoCompressionError.EncodingFailed, "got $err")
    }

    @Test
    fun posixEacces_mapsToIoFailed() {
        val err = mapToVideoError(posixError(13))
        assertTrue(err is VideoCompressionError.IoFailed, "got $err")
    }

    @Test
    fun posixEnospc_mapsToIoFailed() {
        val err = mapToVideoError(posixError(28))
        assertTrue(err is VideoCompressionError.IoFailed, "got $err")
    }

    @Test
    fun posixEnoent_mapsToIoFailed() {
        val err = mapToVideoError(posixError(2))
        assertTrue(err is VideoCompressionError.IoFailed, "got $err")
    }

    @Test
    fun posixErofs_mapsToIoFailed() {
        val err = mapToVideoError(posixError(30))
        assertTrue(err is VideoCompressionError.IoFailed, "got $err")
    }

    @Test
    fun unknownDomain_fallsBackToUnknown() {
        val ns = NSError.errorWithDomain(domain = "co.test.unknown", code = 42L, userInfo = null)
        val ex = AVNSErrorException(ns, "test")
        val err = mapToVideoError(ex)
        assertTrue(err is VideoCompressionError.Unknown, "got $err")
    }

    @Test
    fun nonNsErrorThrowable_fallsBackToUnknown() {
        val cause = IllegalStateException("boom")
        val err = mapToVideoError(cause)
        assertTrue(err is VideoCompressionError.Unknown, "got $err")
        assertEquals(cause, err.cause)
    }

    @Test
    fun alreadyTypedError_passesThrough() {
        val typed = VideoCompressionError.IoFailed("preexisting")
        val err = mapToVideoError(typed)
        assertSame(typed, err)
    }
}
