/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.AVNSErrorException
import co.crackn.kompressor.internal.NsErrorBand
import co.crackn.kompressor.internal.classifyNSError
import co.crackn.kompressor.internal.describeNsError
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Maps an iOS-side throwable thrown from the AVFoundation pipeline into the typed
 * [VideoCompressionError] hierarchy. Mirrors the Android Media3 `toVideoCompressionError`
 * adapter so callers can `when`-branch identically across platforms.
 *
 * Inputs we recognise:
 *  - [AVNSErrorException] carrying the underlying `NSError` — classified by domain + code via
 *    the shared [classifyNSError].
 *  - Already-typed [VideoCompressionError] — returned unchanged.
 *  - Anything else — wrapped in [VideoCompressionError.Unknown] with the message + cause.
 */
internal fun mapToVideoError(cause: Throwable): VideoCompressionError {
    if (cause is VideoCompressionError) return cause
    val nsError = (cause as? AVNSErrorException)?.nsError
    val detail = describeNsError(nsError, cause)
    val band = classifyNSError(nsError) ?: NsErrorBand.Unknown
    return when (band) {
        NsErrorBand.UnsupportedSource -> VideoCompressionError.UnsupportedSourceFormat(detail, cause)
        NsErrorBand.Decoding -> VideoCompressionError.DecodingFailed(detail, cause)
        NsErrorBand.Encoding -> VideoCompressionError.EncodingFailed(detail, cause)
        NsErrorBand.Io -> VideoCompressionError.IoFailed(detail, cause)
        NsErrorBand.Unknown -> VideoCompressionError.Unknown(detail, cause)
    }
}
