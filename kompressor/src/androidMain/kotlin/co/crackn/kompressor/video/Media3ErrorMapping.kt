/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import androidx.media3.transformer.ExportException
import co.crackn.kompressor.Media3ErrorBand
import co.crackn.kompressor.classifyMedia3ErrorBand

/**
 * Maps a Media3 [ExportException] into the library's typed [VideoCompressionError] hierarchy.
 *
 * The int → band classification lives in [classifyMedia3ErrorBand] (shared with audio). This
 * adapter turns a [Media3ErrorBand] into the video-flavoured sealed subtype and embeds a
 * [sourceDescription] diagnostic (typically `MediaMetadataRetriever` output) into the message.
 */
internal fun ExportException.toVideoCompressionError(
    sourceDescription: String? = null,
): VideoCompressionError {
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
    return classifyExportErrorCode(errorCode, detail, this)
}

/**
 * Pure mapping from a Media3 error-code int to the typed video error. Extracted for testability —
 * constructing real [ExportException] instances across Media3 versions is awkward, but the
 * classification logic is easy to exercise directly.
 */
internal fun classifyExportErrorCode(
    errorCode: Int,
    detail: String,
    cause: Throwable?,
): VideoCompressionError = when (classifyMedia3ErrorBand(errorCode)) {
    Media3ErrorBand.UnsupportedSource -> VideoCompressionError.UnsupportedSourceFormat(detail, cause)
    Media3ErrorBand.Decoding -> VideoCompressionError.DecodingFailed(detail, cause)
    Media3ErrorBand.Encoding -> VideoCompressionError.EncodingFailed(detail, cause)
    Media3ErrorBand.Io -> VideoCompressionError.IoFailed(detail, cause)
    Media3ErrorBand.Unknown -> VideoCompressionError.Unknown(detail, cause)
}
