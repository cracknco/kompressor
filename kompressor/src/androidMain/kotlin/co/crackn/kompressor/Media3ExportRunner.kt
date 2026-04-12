package co.crackn.kompressor

import android.media.MediaCodecList
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wraps [Transformer]'s listener + progress-holder API in a cancellable suspend function.
 *
 * Shared by [co.crackn.kompressor.video.AndroidVideoCompressor] and
 * [co.crackn.kompressor.audio.AndroidAudioCompressor] — the Media3 → coroutines adapter is
 * identical regardless of whether we're compressing a video or an audio-only export.
 *
 * The [Transformer] must be started and polled on its application-looper thread — the caller is
 * expected to run the whole export on [kotlinx.coroutines.Dispatchers.Main].
 */
internal suspend fun awaitMedia3Export(
    transformer: Transformer,
    item: EditedMediaItem,
    outputPath: String,
    onProgress: suspend (Float) -> Unit,
) = coroutineScope {
    val progressJob = launchMedia3ProgressPoller(transformer, onProgress)
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    transformer.removeListener(this)
                    // Guard against the cancel-race: invokeOnCancellation may have already
                    // cancelled the continuation before the Main-looper drains this callback.
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    transformer.removeListener(this)
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
            continuation.invokeOnCancellation {
                transformer.removeListener(listener)
                transformer.cancel()
            }
            transformer.addListener(listener)
            transformer.start(item, outputPath)
        }
    } finally {
        progressJob.cancel()
    }
}

private fun CoroutineScope.launchMedia3ProgressPoller(
    transformer: Transformer,
    onProgress: suspend (Float) -> Unit,
) = launch {
    val holder = ProgressHolder()
    var lastReported = 0f
    while (isActive) {
        val state = transformer.getProgress(holder)
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            val progress = (holder.progress / MEDIA3_PROGRESS_PERCENT_MAX).coerceIn(0f, 1f)
            if (progress - lastReported >= MEDIA3_PROGRESS_REPORT_THRESHOLD) {
                onProgress(progress)
                lastReported = progress
            }
        }
        delay(MEDIA3_PROGRESS_POLL_INTERVAL_MS)
    }
}

/**
 * Collect MIME types declared by the platform's [MediaCodecList] for the requested role
 * (encoder / decoder) and media type prefix (e.g. `"video/"`, `"audio/"`).
 *
 * Filtered through [MediaCodecList.REGULAR_CODECS] to exclude experimental / vendor-only codecs
 * that the device advertises but that don't reliably round-trip.
 */
internal fun collectCodecMimeTypes(isEncoder: Boolean, mediaTypePrefix: String): Set<String> =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .asSequence()
        .filter { it.isEncoder == isEncoder }
        .flatMap { it.supportedTypes.asSequence() }
        .filter { it.startsWith(mediaTypePrefix) }
        .toSet()

private const val MEDIA3_PROGRESS_POLL_INTERVAL_MS = 100L
private const val MEDIA3_PROGRESS_REPORT_THRESHOLD = 0.01f
private const val MEDIA3_PROGRESS_PERCENT_MAX = 100f
