package co.crackn.kompressor.video

import androidx.media3.transformer.ExportException

/**
 * Maps a Media3 [ExportException] into the library's typed [VideoCompressionError]
 * hierarchy. The mapping follows the documented error-code ranges in
 * `androidx.media3.transformer.ExportException`:
 *
 * - `ERROR_CODE_IO_*` → [VideoCompressionError.IoFailed]
 * - `ERROR_CODE_DECODER_INIT_FAILED`, `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`
 *   → [VideoCompressionError.UnsupportedSourceFormat]
 * - Other `ERROR_CODE_DECODING_*` → [VideoCompressionError.DecodingFailed]
 * - `ERROR_CODE_ENCODER_*`, `ERROR_CODE_MUXING_*` → [VideoCompressionError.EncodingFailed]
 * - Everything else → [VideoCompressionError.Unknown]
 *
 * The [sourceDescription] parameter is an optional diagnostic string — typically
 * built from [android.media.MediaMetadataRetriever] on the error path — that we
 * embed into the error message so users see e.g. "HEVC Main 10, 3840x2160" in
 * logs / crash reports instead of an opaque error code.
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
 * Pure mapping from a Media3 error-code int to the typed error. Extracted for
 * testability — constructing real [ExportException] instances across Media3
 * versions is awkward, but the classification logic is easy to exercise
 * directly.
 */
internal fun classifyExportErrorCode(
    errorCode: Int,
    detail: String,
    cause: Throwable?,
): VideoCompressionError = when (errorCode) {
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    ->
        VideoCompressionError.UnsupportedSourceFormat(detail, cause)

    ExportException.ERROR_CODE_DECODING_FAILED ->
        VideoCompressionError.DecodingFailed(detail, cause)

    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
    ExportException.ERROR_CODE_ENCODING_FAILED,
    ExportException.ERROR_CODE_MUXING_FAILED,
    ->
        VideoCompressionError.EncodingFailed(detail, cause)

    else -> when (errorCode / THOUSAND) {
        IO_BAND -> VideoCompressionError.IoFailed(detail, cause)
        DECODING_BAND -> VideoCompressionError.DecodingFailed(detail, cause)
        ENCODING_BAND,
        VIDEO_FRAME_PROCESSING_BAND,
        AUDIO_PROCESSING_BAND,
        MUXING_BAND,
        -> VideoCompressionError.EncodingFailed(detail, cause)
        else -> VideoCompressionError.Unknown(detail, cause)
    }
}

// Error-code bands defined in androidx.media3.common.PlaybackException /
// androidx.media3.transformer.ExportException: 1xxx misc, 2xxx IO, 3xxx
// decoding, 4xxx encoding, 5xxx video frame processing, 6xxx audio
// processing, 7xxx muxing.
private const val THOUSAND = 1000
private const val IO_BAND = 2
private const val DECODING_BAND = 3
private const val ENCODING_BAND = 4
private const val VIDEO_FRAME_PROCESSING_BAND = 5
private const val AUDIO_PROCESSING_BAND = 6
private const val MUXING_BAND = 7
