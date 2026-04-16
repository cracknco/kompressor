/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.AVNSErrorException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSPOSIXErrorDomain

// AVFoundation error codes — see AVError.h. Only the codes we explicitly classify are listed
// here; everything else falls through to the band heuristics below or to `Unknown`.
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

// POSIX errno values (errno.h). Kotlin/Native does not expose these as constants, so we hardcode
// the values that surface inside `NSPOSIXErrorDomain` NSErrors from AVFoundation / NSFileManager.
private const val POSIX_EACCES = 13
private const val POSIX_ENOSPC = 28
private const val POSIX_ENOENT = 2
private const val POSIX_EROFS = 30

/**
 * Maps an iOS-side throwable thrown from the AVFoundation pipeline into the typed
 * [VideoCompressionError] hierarchy. Mirrors the Android Media3 `toVideoCompressionError`
 * adapter so callers can `when`-branch identically across platforms.
 *
 * Inputs we recognise:
 *  - [AVNSErrorException] carrying the underlying [NSError] — classified by domain + code.
 *  - Already-typed [VideoCompressionError] — returned unchanged.
 *  - Anything else — wrapped in [VideoCompressionError.Unknown] with the message + cause.
 */
internal fun mapToVideoError(cause: Throwable): VideoCompressionError {
    if (cause is VideoCompressionError) return cause
    val nsError = (cause as? AVNSErrorException)?.nsError
    val detail = describe(nsError, cause)
    val band = nsError?.let(::classifyNSError) ?: Band.Unknown
    return build(band, detail, cause)
}

private enum class Band { UnsupportedSource, Decoding, Encoding, Io, Unknown }

private fun classifyNSError(error: NSError): Band? {
    val domain = error.domain
    val code = error.code.toInt()
    return when (domain) {
        AVFoundationErrorDomain -> classifyAvFoundationCode(code)
        NSPOSIXErrorDomain -> classifyPosixCode(code)
        else -> null
    }
}

private fun classifyAvFoundationCode(code: Int): Band = when (code) {
    AV_ERR_FILE_FORMAT_NOT_RECOGNIZED,
    AV_ERR_FILE_FAILED_TO_PARSE,
    AV_ERR_MEDIA_DATA_NOT_FOUND,
    -> Band.UnsupportedSource

    AV_ERR_UNKNOWN,
    AV_ERR_NO_DATA_CAPTURED,
    AV_ERR_MEDIA_DATA_IN_WRONG_FORMAT,
    -> Band.Decoding

    AV_ERR_DISK_FULL,
    AV_ERR_SESSION_NOT_RUNNING,
    -> Band.Io

    AV_ERR_EXPORT_FAILED,
    AV_ERR_OUT_OF_MEMORY,
    -> Band.Encoding

    else -> Band.Unknown
}

private fun classifyPosixCode(code: Int): Band = when (code) {
    POSIX_EACCES, POSIX_ENOSPC, POSIX_ENOENT, POSIX_EROFS -> Band.Io
    else -> Band.Unknown
}

private fun build(band: Band, detail: String, cause: Throwable): VideoCompressionError = when (band) {
    Band.UnsupportedSource -> VideoCompressionError.UnsupportedSourceFormat(detail, cause)
    Band.Decoding -> VideoCompressionError.DecodingFailed(detail, cause)
    Band.Encoding -> VideoCompressionError.EncodingFailed(detail, cause)
    Band.Io -> VideoCompressionError.IoFailed(detail, cause)
    Band.Unknown -> VideoCompressionError.Unknown(detail, cause)
}

private fun describe(error: NSError?, cause: Throwable): String {
    if (error == null) return cause.message ?: cause::class.simpleName.orEmpty()
    return "${error.domain}#${error.code} — ${error.localizedDescription}"
}
