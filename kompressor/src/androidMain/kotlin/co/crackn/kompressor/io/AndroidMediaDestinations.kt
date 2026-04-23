/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream
import okio.sink

/**
 * Create a [MediaDestination] from an Android [Uri].
 *
 * **MediaStore output** — when [uri]'s authority is [MediaStore.AUTHORITY] (`"media"`),
 * Kompressor automatically handles the `IS_PENDING` flag pattern required for scoped storage on
 * Android 10+ : set `IS_PENDING=1` before writing, stream the compressed bytes into the
 * MediaStore entry via `ContentResolver.openOutputStream`, then clear `IS_PENDING=0` so the
 * file becomes visible in the gallery. If the final clear step fails on a custom ContentProvider
 * that mimics the MediaStore authority but does not implement the `IS_PENDING` contract,
 * Kompressor logs a WARN and still returns success — the compressed output is written correctly,
 * only the pending flag could not be cleared. See [MediaStoreOutputStrategy] for the full
 * contract. The "best-effort, don't crash on a misbehaving provider" stance is deliberate: we
 * cannot fix a broken custom provider, but we must not fail a compression because of one.
 *
 * For non-MediaStore `content://` URIs (SAF documents, custom providers), Kompressor uses
 * `ContentResolver.openOutputStream(uri)` directly — no `IS_PENDING` dance is attempted.
 *
 * Remote schemes `http` and `https` are rejected by design:
 * ```
 * IllegalArgumentException: Remote URLs not supported. Write locally first then upload.
 * ```
 * The message is a cross-platform invariant shared with the iOS builder sibling (T5).
 *
 * @param uri The Android URI to write to. Must be `file` or `content`.
 * @return A [MediaDestination.Local] that the Android compressors recognise as a URI destination.
 * @throws IllegalArgumentException if [uri]'s scheme is `http` or `https`, or if the scheme is
 *   otherwise unsupported.
 */
public fun MediaDestination.Companion.of(uri: Uri): MediaDestination.Local =
    when (uri.scheme) {
        "file", "content" -> AndroidUriMediaDestination(uri)
        // See [MediaSource.Companion.of(Uri)] for the `rejectScheme` helper rationale — same
        // Nothing-returning pattern so the expression-bodied `when` unifies at
        // `MediaDestination.Local` instead of `Any`.
        "http", "https" -> rejectScheme(MediaSourceRejections.REMOTE_URL_OUTPUT)
        else -> rejectScheme(
            "Unsupported URI scheme: ${uri.scheme ?: "<null>"}. Expected 'file' or 'content'.",
        )
    }

/**
 * Create a [MediaDestination] from a Java [OutputStream].
 *
 * For video and audio Kompressor writes to a private temp file first then copies the bytes
 * into the sink (Media3 Transformer / `Bitmap.compress` both want file or sink outputs, not
 * raw [java.io.OutputStream]). For images the stream is written to directly.
 *
 * @param stream The output stream to write the compressed bytes to.
 * @param closeOnFinish If `true` (default), Kompressor closes the stream at the end of
 *   compression (success or failure). Set to `false` when the stream lifecycle is externally
 *   managed.
 * @return A [MediaDestination.Local.Stream] wrapping the output.
 */
public fun MediaDestination.Companion.of(
    stream: OutputStream,
    closeOnFinish: Boolean = true,
): MediaDestination.Local =
    MediaDestination.Local.Stream(stream.sink(), closeOnFinish = closeOnFinish)

/** Sibling of [AndroidMediaSources]' private `rejectScheme`; see that file for rationale. */
@Suppress("TooGenericExceptionThrown")
private fun rejectScheme(message: String): Nothing = throw IllegalArgumentException(message)

/**
 * Internal marker wrapper for `content://` / `file://` URI outputs. Carries the
 * [isMediaStoreUri] flag that the dispatch in [toAndroidOutputHandle] consults to decide
 * whether to route the write through [MediaStoreOutputStrategy] (IS_PENDING-aware) or a plain
 * `ContentResolver.openOutputStream(uri)`.
 */
internal class AndroidUriMediaDestination(val uri: Uri) : MediaDestination.Local {

    /**
     * `true` when [uri]'s authority matches [MediaStore.AUTHORITY] — i.e. the URI was minted by
     * a `contentResolver.insert(MediaStore.X.Media.EXTERNAL_CONTENT_URI, ...)` call. Custom
     * providers that alias the `"media"` authority without implementing `IS_PENDING` are handled
     * by [MediaStoreOutputStrategy]'s graceful fallback.
     */
    val isMediaStoreUri: Boolean
        get() = uri.authority == MediaStore.AUTHORITY

    override fun equals(other: Any?): Boolean = other is AndroidUriMediaDestination && other.uri == uri
    override fun hashCode(): Int = uri.hashCode()
    override fun toString(): String = "AndroidUriMediaDestination(uri=$uri, mediastore=$isMediaStoreUri)"
}
