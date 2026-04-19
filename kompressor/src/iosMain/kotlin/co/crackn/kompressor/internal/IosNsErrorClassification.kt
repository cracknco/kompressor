/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.internal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSPOSIXErrorDomain

// AVFoundation error codes — see AVError.h. Only the codes we explicitly classify are listed here;
// everything else falls through to `NsErrorBand.Unknown`.
private const val AV_ERR_UNKNOWN = -11800
private const val AV_ERR_FILE_FORMAT_NOT_RECOGNIZED = -11828
private const val AV_ERR_FILE_FAILED_TO_PARSE = -11829
private const val AV_ERR_MEDIA_DATA_NOT_FOUND = -11831
private const val AV_ERR_MEDIA_DATA_IN_WRONG_FORMAT = -11832
private const val AV_ERR_NO_DATA_CAPTURED = -11841
private const val AV_ERR_DISK_FULL = -11823
private const val AV_ERR_SESSION_NOT_RUNNING = -11824
private const val AV_ERR_EXPORT_FAILED = -11820
private const val AV_ERR_OUT_OF_MEMORY = -11821

// POSIX errno values (errno.h). Kotlin/Native doesn't expose these as constants, so we hardcode
// the values that surface inside `NSPOSIXErrorDomain` NSErrors from AVFoundation / NSFileManager.
private const val POSIX_EACCES = 13
private const val POSIX_ENOSPC = 28
private const val POSIX_ENOENT = 2
private const val POSIX_EROFS = 30

/**
 * Platform-agnostic error band for AVFoundation / POSIX failures surfaced as [NSError].
 * Shared by the iOS audio and video error mappers — they pick the same bucket because
 * AVError.h documents these codes once with the same semantics across media types.
 */
internal enum class NsErrorBand { UnsupportedSource, Decoding, Encoding, Io, Unknown }

/**
 * Classifies an [NSError] from AVFoundation's export / reader / writer pipelines into a
 * coarse-grained [NsErrorBand]. Returns `null` when [error] is `null` so callers can decide
 * between "no NSError => probably a bug in our Kotlin glue" and "we have an NSError we can
 * classify". A non-null error with an unrecognised domain is classified [NsErrorBand.Unknown]
 * rather than `null` — an unexpected domain is still a classification result, it's "we
 * genuinely don't know which band this fits in".
 */
internal fun classifyNSError(error: NSError?): NsErrorBand? {
    if (error == null) return null
    val domain = error.domain
    val code = error.code.toInt()
    return when (domain) {
        AVFoundationErrorDomain -> classifyAvFoundationCode(code)
        NSPOSIXErrorDomain -> classifyPosixCode(code)
        else -> NsErrorBand.Unknown
    }
}

private fun classifyAvFoundationCode(code: Int): NsErrorBand = when (code) {
    AV_ERR_FILE_FORMAT_NOT_RECOGNIZED,
    AV_ERR_FILE_FAILED_TO_PARSE,
    AV_ERR_MEDIA_DATA_NOT_FOUND,
    -> NsErrorBand.UnsupportedSource

    AV_ERR_UNKNOWN,
    AV_ERR_NO_DATA_CAPTURED,
    AV_ERR_MEDIA_DATA_IN_WRONG_FORMAT,
    -> NsErrorBand.Decoding

    AV_ERR_DISK_FULL,
    AV_ERR_SESSION_NOT_RUNNING,
    -> NsErrorBand.Io

    AV_ERR_EXPORT_FAILED,
    AV_ERR_OUT_OF_MEMORY,
    -> NsErrorBand.Encoding

    else -> NsErrorBand.Unknown
}

private fun classifyPosixCode(code: Int): NsErrorBand = when (code) {
    POSIX_EACCES, POSIX_ENOSPC, POSIX_ENOENT, POSIX_EROFS -> NsErrorBand.Io
    else -> NsErrorBand.Unknown
}

/**
 * Produces a short human-readable detail string for an [NSError] / [cause] pair, suitable for
 * embedding in the `details` field of a typed compression error.
 */
internal fun describeNsError(error: NSError?, cause: Throwable): String {
    if (error == null) return cause.message ?: cause::class.simpleName.orEmpty()
    return "${error.domain}#${error.code} — ${error.localizedDescription}"
}
