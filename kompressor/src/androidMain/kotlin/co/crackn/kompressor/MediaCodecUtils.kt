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

internal const val CODEC_TIMEOUT_US = 0L
internal const val REMUX_BUFFER_SIZE = 256 * 1024
