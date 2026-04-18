/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.AVNSErrorException
import co.crackn.kompressor.internal.NsErrorBand
import co.crackn.kompressor.internal.classifyNSError
import co.crackn.kompressor.internal.describeNsError
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Maps an iOS-side throwable thrown from the AVFoundation audio pipeline into the typed
 * [AudioCompressionError] hierarchy. Mirrors the Android Media3 audio adapter so callers can
 * `when`-branch identically across platforms.
 *
 * Already-typed [AudioCompressionError] values (notably [AudioCompressionError.UnsupportedConfiguration]
 * thrown by the upfront validators) are returned unchanged.
 */
internal fun mapToAudioError(cause: Throwable): AudioCompressionError {
    if (cause is AudioCompressionError) return cause
    val nsError = (cause as? AVNSErrorException)?.nsError
    val detail = describeNsError(nsError, cause)
    val band = classifyNSError(nsError) ?: NsErrorBand.Unknown
    return when (band) {
        NsErrorBand.UnsupportedSource -> AudioCompressionError.UnsupportedSourceFormat(detail, cause)
        NsErrorBand.Decoding -> AudioCompressionError.DecodingFailed(detail, cause)
        NsErrorBand.Encoding -> AudioCompressionError.EncodingFailed(detail, cause)
        NsErrorBand.Io -> AudioCompressionError.IoFailed(detail, cause)
        NsErrorBand.Unknown -> AudioCompressionError.Unknown(detail, cause)
    }
}
