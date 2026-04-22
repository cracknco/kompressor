/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.InputStream
import okio.source

/**
 * Create a [MediaSource] from an Android content [Uri].
 *
 * Accepts schemes `file` and `content` — typical outputs of
 * `ActivityResultContracts.PickVisualMedia` (`content://media/...`), the Storage Access Framework,
 * or raw `file://` URIs.
 *
 * Remote schemes `http` and `https` are rejected by design:
 * ```
 * IllegalArgumentException: Remote URLs not supported. Download the content locally first.
 * ```
 * The message is a cross-platform invariant shared with the iOS builder sibling (tracked as T5)
 * so a consumer switching platforms sees the identical rejection text.
 *
 * The returned [MediaSource.Local] is consumed by the `compress(MediaSource, MediaDestination, ...)`
 * overload introduced in CRA-92. For `content://` inputs Kompressor defers to the existing
 * `ContentResolver.openInputStream` / `MediaMetadataRetriever(Context, Uri)` plumbing used by the
 * legacy string-based overload; no materialization to disk is performed.
 *
 * @param uri The Android URI to compress from. Must be `file` or `content`.
 * @return A [MediaSource.Local] that the Android compressors recognise as a URI source.
 * @throws IllegalArgumentException if [uri]'s scheme is `http` or `https`, or if the scheme is
 *   otherwise unsupported (null, `ftp`, or any custom scheme Kompressor does not resolve).
 */
public fun MediaSource.Companion.of(uri: Uri): MediaSource.Local =
    when (uri.scheme) {
        "file", "content" -> AndroidUriMediaSource(uri)
        // Bad-scheme branches funnel through [rejectScheme] (Nothing-returning) rather than
        // `require(false) { ... }`: the latter has static return type Unit, which would unify
        // the expression-bodied `when` at `Any` and break the `MediaSource.Local` return type.
        // The rejection message string is the cross-platform invariant consumed by the iOS
        // sibling (T5) — keep byte-identical.
        "http", "https" -> rejectScheme(MediaSourceRejections.REMOTE_URL_INPUT)
        else -> rejectScheme(
            "Unsupported URI scheme: ${uri.scheme ?: "<null>"}. Expected 'file' or 'content'.",
        )
    }

/**
 * Raise [IllegalArgumentException] with [message] and a `Nothing` return type, so the throw-site
 * unifies cleanly inside expression-bodied `when` arms. Direct `throw IllegalArgumentException(...)`
 * is blocked by detekt's `TooGenericExceptionThrown` rule project-wide (see
 * `config/detekt/detekt.yml`); the targeted `@Suppress` is scoped to this one helper so the rest
 * of the file still benefits from the lint.
 */
@Suppress("TooGenericExceptionThrown")
private fun rejectScheme(message: String): Nothing = throw IllegalArgumentException(message)

/**
 * Create a [MediaSource] from a [ParcelFileDescriptor].
 *
 * Kompressor materializes the file descriptor's contents to a private temp file before running
 * compression (Media3 Transformer and `BitmapFactory.decodeFile` both need a real filesystem path)
 * and closes the PFD at the end of compression — success or failure. Consumers who have already
 * resolved a `content://` URI via `contentResolver.openFileDescriptor(uri, "r")` can pass the PFD
 * directly; Kompressor takes ownership of its lifetime.
 *
 * @param pfd The file descriptor to read from. Closed by Kompressor when compression finishes.
 * @return A [MediaSource.Local] that the Android compressors materialize to a temp file.
 */
public fun MediaSource.Companion.of(pfd: ParcelFileDescriptor): MediaSource.Local =
    AndroidPfdMediaSource(pfd)

/**
 * Create a [MediaSource] from a Java [InputStream].
 *
 * For video and audio the stream is materialized to a private temp file before compression
 * (native encoders require seekable inputs); for images the stream is decoded directly. The
 * builder wraps the stream in an [okio.Source] via [okio.source] — consumers already holding
 * an okio [okio.Source] should construct [MediaSource.Local.Stream] themselves rather than round-
 * tripping through [java.io.InputStream].
 *
 * @param stream The input stream to read from.
 * @param closeOnFinish If `true` (default), Kompressor closes the stream at the end of
 *   compression (success or failure). Set to `false` when the stream lifecycle is externally
 *   managed (e.g. shared with an uploader running in parallel).
 * @return A [MediaSource.Local.Stream] wrapping the input.
 */
public fun MediaSource.Companion.of(
    stream: InputStream,
    closeOnFinish: Boolean = true,
): MediaSource.Local =
    MediaSource.Local.Stream(stream.source(), closeOnFinish = closeOnFinish)

/**
 * Internal marker wrapper for `content://` / `file://` URI inputs. The public builder
 * [MediaSource.Companion.of] returns this; the Android compressor dispatch in
 * [toAndroidInputPath] unwraps it back to a string path that the legacy
 * `compress(inputPath, outputPath, ...)` overloads already know how to handle.
 */
internal class AndroidUriMediaSource(val uri: Uri) : MediaSource.Local {
    override fun equals(other: Any?): Boolean = other is AndroidUriMediaSource && other.uri == uri
    override fun hashCode(): Int = uri.hashCode()
    override fun toString(): String = "AndroidUriMediaSource(uri=$uri)"
}

/**
 * Internal marker wrapper for [ParcelFileDescriptor] inputs. The dispatch in
 * [toAndroidInputPath] materializes the PFD to a private temp file and closes the PFD at the
 * end of compression.
 *
 * `equals` / `hashCode` use the file-descriptor number rather than identity so two wrappers
 * around the same OS FD compare equal — the FD number is a stable, collision-free handle while
 * the PFD object itself is a short-lived wrapper that can be reconstructed by the caller.
 */
internal class AndroidPfdMediaSource(val pfd: ParcelFileDescriptor) : MediaSource.Local {
    override fun equals(other: Any?): Boolean = other is AndroidPfdMediaSource && other.pfd.fd == pfd.fd
    override fun hashCode(): Int = pfd.fd.hashCode()
    override fun toString(): String = "AndroidPfdMediaSource(fd=${pfd.fd})"
}
