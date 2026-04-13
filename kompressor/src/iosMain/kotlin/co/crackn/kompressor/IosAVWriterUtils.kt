package co.crackn.kompressor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusFailed
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Polls until [input] is ready to accept more media data, or throws if
 * [writer] enters a failed state or [WRITER_READY_TIMEOUT_MS] is exceeded.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitWriterReady(
    writer: AVAssetWriter,
    input: AVAssetWriterInput,
) {
    var waited = 0L
    while (!input.readyForMoreMediaData) {
        if (writer.status == AVAssetWriterStatusFailed) {
            val err = writer.error
            if (err != null) {
                throw AVNSErrorException(err, "AVAssetWriter failed while waiting")
            }
            error("AVAssetWriter failed while waiting: unknown")
        }
        check(waited < WRITER_READY_TIMEOUT_MS) {
            "AVAssetWriterInput not ready after ${waited}ms (writer status: ${writer.status})"
        }
        currentCoroutineContext().ensureActive()
        delay(WRITER_POLL_INTERVAL_MS)
        waited += WRITER_POLL_INTERVAL_MS
    }
}

/**
 * Suspends until [writer] finishes writing, with cancellation support.
 *
 * Calls [AVAssetWriter.cancelWriting] if the coroutine is cancelled.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitWriterFinish(writer: AVAssetWriter) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { writer.cancelWriting() }
        writer.finishWritingWithCompletionHandler {
            if (writer.status == AVAssetWriterStatusCompleted) {
                continuation.resume(Unit)
            } else {
                val err = writer.error
                val ex = if (err != null) {
                    AVNSErrorException(err, "AVAssetWriter failed")
                } else {
                    IllegalStateException("AVAssetWriter failed: unknown")
                }
                continuation.resumeWithException(ex)
            }
        }
    }
}

/** Checks that [writer] completed successfully, throwing otherwise. */
@OptIn(ExperimentalForeignApi::class)
internal fun checkWriterCompleted(writer: AVAssetWriter) {
    if (writer.status != AVAssetWriterStatusCompleted) {
        val err = writer.error
        if (err != null) {
            throw AVNSErrorException(err, "AVAssetWriter not completed")
        }
        error("AVAssetWriter not completed: unknown")
    }
}

/**
 * Suspends until [session] export completes, with cancellation support.
 *
 * Calls [platform.AVFoundation.AVAssetExportSession.cancelExport] if the coroutine is cancelled.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitExportSession(session: platform.AVFoundation.AVAssetExportSession) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { session.cancelExport() }
        session.exportAsynchronouslyWithCompletionHandler {
            when (session.status) {
                platform.AVFoundation.AVAssetExportSessionStatusCompleted -> {
                    continuation.resume(Unit)
                }
                platform.AVFoundation.AVAssetExportSessionStatusFailed ->
                    continuation.resumeWithException(exportFailureException(session))
                platform.AVFoundation.AVAssetExportSessionStatusCancelled -> {
                    continuation.resumeWithException(
                        CancellationException("Export cancelled"),
                    )
                }
                else -> {
                    continuation.resumeWithException(
                        IllegalStateException("Unexpected export status: ${session.status}"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun exportFailureException(
    session: platform.AVFoundation.AVAssetExportSession,
): Throwable {
    val err = session.error ?: return IllegalStateException("Export failed: unknown")
    return AVNSErrorException(err, "Export failed")
}

internal const val WRITER_POLL_INTERVAL_MS = 10L

/**
 * Cap for [awaitWriterReady] — guards against a genuine hang (writer wedged in an internal
 * state machine, no progress possible) without masking slow legitimate encode starts. Was
 * 10 s historically; bumped to 30 s after iOS simulator stalls on AAC-LC `44.1 kHz × stereo`
 * at the 64 kbps boundary started reliably exceeding 10 s on fresh simulators (AudioToolbox
 * appears to do extra initialisation work for low-bitrate stereo that legitimately blocks
 * the input for up to ~15 s on the first buffer). Real production devices hit this path in
 * <1 s so the extra headroom is simulator-only slack; a real deadlock still trips the check.
 */
internal const val WRITER_READY_TIMEOUT_MS = 30_000L
