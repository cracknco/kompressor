/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.media3.transformer.ExportException

/**
 * Canonical buckets for Media3 [ExportException] error codes, extracted so that both video and
 * audio compressors can map platform error codes into their respective typed error hierarchies
 * without duplicating the classification logic.
 *
 * Semantics follow `androidx.media3.common.PlaybackException` / [ExportException]:
 * - `1xxx` misc (runtime, not-yet-playable, etc.)
 * - `2xxx` IO (file not found, network, disk full)
 * - `3xxx` decoding (format unsupported, decoder init, decoder failure)
 * - `4xxx` encoding (encoder init, encoder failure, format unsupported)
 * - `5xxx` video frame processing
 * - `6xxx` audio processing
 * - `7xxx` muxing
 */
internal enum class Media3ErrorBand {
    /** Source codec/container not supported by any decoder on this device. */
    UnsupportedSource,

    /** Decoder initialised then failed mid-stream (corrupt file, OEM codec bug). */
    Decoding,

    /** Encoder / frame processor / muxer failed, or the target format isn't supported. */
    Encoding,

    /** Read/write I/O failure on the input or output path. */
    Io,

    /** Anything we couldn't place into the above buckets — including the `1xxx` misc band. */
    Unknown,
}

/**
 * Pure mapping from a Media3 [ExportException.errorCode] int to a [Media3ErrorBand].
 *
 * Extracted into its own function (and parameterised on `Int` rather than [ExportException]) so
 * host tests can exercise every code / band boundary without constructing real exceptions, which
 * is awkward across Media3 versions.
 */
@Suppress("CyclomaticComplexMethod")
internal fun classifyMedia3ErrorBand(errorCode: Int): Media3ErrorBand = when (errorCode) {
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> Media3ErrorBand.UnsupportedSource

    ExportException.ERROR_CODE_DECODING_FAILED -> Media3ErrorBand.Decoding

    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
    ExportException.ERROR_CODE_ENCODING_FAILED,
    ExportException.ERROR_CODE_MUXING_FAILED,
    -> Media3ErrorBand.Encoding

    else -> when (errorCode / THOUSAND) {
        // 1xxx — Media3 emits ERROR_CODE_FAILED_RUNTIME_CHECK (1004) and the generic asset-
        // loader codes (1000) here when the input bytes don't match any registered extractor.
        // For our pipeline that always means "we cannot read the source format" — bucket as
        // UnsupportedSource so callers see a typed error instead of `Unknown`.
        ASSET_LOADER_BAND -> Media3ErrorBand.UnsupportedSource
        IO_BAND -> Media3ErrorBand.Io
        DECODING_BAND -> Media3ErrorBand.Decoding
        ENCODING_BAND,
        VIDEO_FRAME_PROCESSING_BAND,
        AUDIO_PROCESSING_BAND,
        MUXING_BAND,
        -> Media3ErrorBand.Encoding
        else -> Media3ErrorBand.Unknown
    }
}

private const val THOUSAND = 1000
private const val ASSET_LOADER_BAND = 1
private const val IO_BAND = 2
private const val DECODING_BAND = 3
private const val ENCODING_BAND = 4
private const val VIDEO_FRAME_PROCESSING_BAND = 5
private const val AUDIO_PROCESSING_BAND = 6
private const val MUXING_BAND = 7
