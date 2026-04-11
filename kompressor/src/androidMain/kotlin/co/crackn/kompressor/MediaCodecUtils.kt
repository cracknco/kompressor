package co.crackn.kompressor

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer

/** Safe [MediaFormat.getLong] that returns 0 if the key is missing or wrong type. */
internal fun MediaFormat.safeLong(key: String): Long =
    try { getLong(key) } catch (_: Exception) { 0L }

/** Safe [MediaFormat.getInteger] that returns 0 if the key is missing or wrong type. */
internal fun MediaFormat.safeInt(key: String): Int =
    try { getInteger(key) } catch (_: Exception) { 0 }

/** Stops (if started) and releases a [MediaCodec] without throwing. */
internal fun MediaCodec.safeRelease() {
    try { stop() } catch (_: IllegalStateException) { /* not started */ }
    release()
}

/** Stops (if started) and releases a [MediaMuxer] without throwing. */
internal fun MediaMuxer.safeStopAndRelease() {
    try { stop() } catch (_: IllegalStateException) { /* not started */ }
    release()
}

/**
 * Reports compression progress based on presentation time vs total duration.
 *
 * Only fires [onProgress] when the change exceeds [PROGRESS_REPORT_THRESHOLD]
 * to avoid flooding the callback on every frame.
 *
 * @return the last reported progress value.
 */
internal suspend fun reportMediaCodecProgress(
    currentTimeUs: Long,
    totalDurationUs: Long,
    lastReported: Float,
    onProgress: suspend (Float) -> Unit,
): Float {
    if (totalDurationUs <= 0 || currentTimeUs <= 0) return lastReported
    val fraction = (currentTimeUs.toFloat() / totalDurationUs).coerceAtMost(1f)
    val progress = PROGRESS_SETUP + PROGRESS_TRANSCODE_RANGE * fraction
    val shouldReport = progress - lastReported >= PROGRESS_REPORT_THRESHOLD
    if (shouldReport) onProgress(progress)
    return if (shouldReport) progress else lastReported
}

internal const val CODEC_TIMEOUT_US = 0L
internal const val REMUX_BUFFER_SIZE = 256 * 1024
internal const val PROGRESS_SETUP = 0.05f
internal const val PROGRESS_TRANSCODE_RANGE = 0.90f
internal const val PROGRESS_REPORT_THRESHOLD = 0.01f
