package co.crackn.kompressor

/**
 * Reports compression progress based on presentation time vs total duration.
 *
 * Only fires [onProgress] when the change exceeds [PROGRESS_REPORT_THRESHOLD]
 * to avoid flooding the callback on every frame.
 *
 * Returns the last reported progress value so callers can chain calls:
 * `lastReported = reportMediaCodecProgress(pts, total, lastReported, onProgress)`.
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

internal const val PROGRESS_SETUP = 0.05f
internal const val PROGRESS_TRANSCODE_RANGE = 0.90f
internal const val PROGRESS_REPORT_THRESHOLD = 0.01f
