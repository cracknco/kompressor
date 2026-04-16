/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.media.MediaCodecList
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * expected to run the whole export on [kotlinx.coroutines.Dispatchers.Main]. Cancellation
 * callbacks (which may fire from arbitrary threads) are explicitly hopped to the Main looper
 * before touching the [Transformer].
 *
 * Two overloads exist: one taking an [EditedMediaItem] (the default for audio + non-HDR video)
 * and one taking a [Composition] (used by video paths that need [Composition.Builder.setHdrMode]).
 * Both share the same listener / progress / cancellation glue.
 */
internal suspend fun awaitMedia3Export(
    transformer: Transformer,
    composition: Composition,
    outputPath: String,
    onProgress: suspend (Float) -> Unit,
) = awaitMedia3Export(transformer, onProgress) { transformer.start(composition, outputPath) }

internal suspend fun awaitMedia3Export(
    transformer: Transformer,
    item: EditedMediaItem,
    outputPath: String,
    onProgress: suspend (Float) -> Unit,
) = awaitMedia3Export(transformer, onProgress) { transformer.start(item, outputPath) }

private suspend fun awaitMedia3Export(
    transformer: Transformer,
    onProgress: suspend (Float) -> Unit,
    startTransformer: () -> Unit,
) = coroutineScope {
    val progressJob = launchMedia3ProgressPoller(transformer, onProgress)
    // Track whether the export needs a synchronous cancel on the caller's withContext(Main)
    // frame when cancellation arrives. `Transformer.cancel()` is a synchronous finaliser —
    // after it returns, the muxer has closed its FileOutputStream and won't re-touch the
    // output path. Running it *outside* the cancellation throw path (via invokeOnCancellation
    // posted to Main) is a race: the coroutine unwinds while Media3 is still flushing, and a
    // partial-output cleanup (File.delete) loses to a late muxer write that recreates the file.
    // Instead we `transformer.cancel()` from the catch block, which is guaranteed to run on
    // the same Main-looper frame the caller wrapped us in.
    var started = false
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
                // Best-effort remove the listener so Media3 doesn't call back into a dead
                // continuation; the authoritative transformer.cancel() is done synchronously
                // in the outer catch below where we're guaranteed to be on Main.
                mainHandler.post { transformer.removeListener(listener) }
            }
            try {
                transformer.addListener(listener)
                startTransformer()
                started = true
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                // Synchronous failures from addListener/start would otherwise leak the listener.
                // We're already on the Main looper (caller wraps this in withContext(Main)), so
                // removeListener is safe to call directly.
                transformer.removeListener(listener)
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
    } catch (ce: CancellationException) {
        // Synchronously cancel Media3 on the Main looper so the muxer fully closes its
        // FileOutputStream *before* the coroutine unwinds and the surrounding
        // `deletingOutputOnFailure` runs its File.delete(). Without this, Media3 may still be
        // writing — or finalising its moov — when we delete, and recreate the file afterwards.
        if (started) transformer.cancel()
        throw ce
    } finally {
        progressJob.cancel()
    }
}

private fun CoroutineScope.launchMedia3ProgressPoller(
    transformer: Transformer,
    onProgress: suspend (Float) -> Unit,
    // Pin the poller to Main explicitly so a future caller that forgets to wrap in
    // withContext(Dispatchers.Main) doesn't silently invoke Transformer.getProgress off-thread.
) = launch(Dispatchers.Main) {
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
 * A Media3 [androidx.media3.muxer.Muxer.Factory] that skips the default 400 KB `moov`-reservation.
 *
 * Media3 1.10's [androidx.media3.transformer.DefaultMuxer] delegates to [InAppMp4Muxer] which
 * defaults to [androidx.media3.muxer.Mp4Muxer.Builder.setAttemptStreamableOutputEnabled] `= true`
 * and reserves 400 000 bytes after `ftyp` for a front-loaded `moov` box. Short outputs (audio:
 * 30–150 KB, short test-fixture video: < 200 KB) never fill that reservation — the remainder
 * stays as a `free` padding box, bloating every output by ≥ 400 KB and masking any
 * bitrate-vs-size assertion.
 *
 * `setFreeSpaceAfterFileTypeBoxBytes(1)` is the one knob [InAppMp4Muxer.Factory] exposes to
 * bypass the 400 KB default: the muxer reserves just 1 byte, discovers at finalize time that
 * the real `moov` doesn't fit, and writes a minimal 9-byte `free` box at the head with the full
 * `moov` relocated to the tail. Net container overhead: ~30 bytes instead of ~400 KB.
 *
 * Shared between [co.crackn.kompressor.audio.AndroidAudioCompressor] and
 * [co.crackn.kompressor.video.AndroidVideoCompressor] — the padding bug is independent of
 * the track type.
 */
internal fun buildTightMp4MuxerFactory(): InAppMp4Muxer.Factory =
    InAppMp4Muxer.Factory().setFreeSpaceAfterFileTypeBoxBytes(1)

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

/**
 * Resolve the size of an input media for [CompressionResult.inputSize]. Handles all three forms
 * the public API accepts: `content://` URIs (via `ContentResolver.openFileDescriptor.statSize`),
 * `file://` URIs (path extracted via [Uri.parse]), and bare filesystem paths.
 *
 * Returns 0 on any failure so the ratio is never poisoned by a thrown exception.
 */
internal fun resolveMediaInputSize(inputPath: String): Long {
    if (inputPath.startsWith("content://")) {
        val uri = Uri.parse(inputPath)
        return runCatching {
            KompressorContext.appContext.contentResolver
                .openFileDescriptor(uri, "r")
                ?.use { it.statSize.coerceAtLeast(0L) }
                ?: 0L
        }.getOrDefault(0L)
    }
    val path = if (inputPath.startsWith("file://")) {
        Uri.parse(inputPath).path ?: inputPath.removePrefix("file://")
    } else {
        inputPath
    }
    return runCatching { File(path).length() }.getOrDefault(0L)
}

/**
 * Normalize an input path into the URI form Media3's [androidx.media3.common.MediaItem.fromUri]
 * expects: `content://` and `file://` schemes pass through, bare filesystem paths are prefixed
 * with `file://`.
 */
internal fun toMediaItemUri(inputPath: String): String =
    if (inputPath.startsWith("content://") || inputPath.startsWith("file://")) {
        inputPath
    } else {
        // Use Uri.fromFile so reserved characters in the path (spaces, #, ?, %, etc.) get
        // properly percent-encoded — raw "file://$path" would break Media3's URI parser on
        // paths containing any of those.
        Uri.fromFile(File(inputPath)).toString()
    }

private val mainHandler = Handler(Looper.getMainLooper())

private const val MEDIA3_PROGRESS_POLL_INTERVAL_MS = 100L
private const val MEDIA3_PROGRESS_REPORT_THRESHOLD = 0.01f
private const val MEDIA3_PROGRESS_PERCENT_MAX = 100f
