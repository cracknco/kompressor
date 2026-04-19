/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import androidx.media3.transformer.ExportException
import co.crackn.kompressor.Media3ErrorBand
import co.crackn.kompressor.classifyMedia3ErrorBand

/**
 * Maps a Media3 [ExportException] into the library's typed [AudioCompressionError] hierarchy.
 *
 * Shares the int → band classification with the video compressor (see
 * [classifyMedia3ErrorBand]) so that both compressors stay in sync if Media3 introduces new
 * error codes or re-bands existing ones.
 *
 * The [sourceDescription] parameter is an optional diagnostic — typically built from
 * [android.media.MediaMetadataRetriever] on the error path — that we embed into the error message
 * so users see e.g. `audio/flac 96000Hz 2ch` in logs / crash reports instead of an opaque code.
 */
internal fun ExportException.toAudioCompressionError(
    sourceDescription: String? = null,
): AudioCompressionError {
    val detail = buildString {
        append(errorCodeName)
        append(" (")
        append(errorCode)
        append(")")
        if (sourceDescription != null) {
            append(" — source: ")
            append(sourceDescription)
        }
        message?.takeIf { it.isNotBlank() }?.let {
            append(" — ")
            append(it)
        }
    }
    return classifyAudioExportErrorCode(errorCode, detail, this)
}

/**
 * Pure mapping from a Media3 error-code int to the typed audio error. Extracted for testability —
 * constructing real [ExportException] instances across Media3 versions is awkward, but the
 * classification logic is easy to exercise directly.
 */
internal fun classifyAudioExportErrorCode(
    errorCode: Int,
    detail: String,
    cause: Throwable?,
): AudioCompressionError = when (classifyMedia3ErrorBand(errorCode)) {
    Media3ErrorBand.UnsupportedSource -> AudioCompressionError.UnsupportedSourceFormat(detail, cause)
    Media3ErrorBand.Decoding -> AudioCompressionError.DecodingFailed(detail, cause)
    Media3ErrorBand.Encoding -> AudioCompressionError.EncodingFailed(detail, cause)
    Media3ErrorBand.Io -> AudioCompressionError.IoFailed(detail, cause)
    Media3ErrorBand.Unknown -> AudioCompressionError.Unknown(detail, cause)
}
