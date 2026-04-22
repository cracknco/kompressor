/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.provider.OpenableColumns
import co.crackn.kompressor.KompressorContext
import java.io.File

/**
 * Android actual of [estimateSourceSize].
 *
 * Dispatch matrix:
 *
 *  | Variant                   | Source of truth                                              |
 *  | ------------------------- | ------------------------------------------------------------ |
 *  | [MediaSource.Local.FilePath] | [File.length] — only returned when the file exists.          |
 *  | [MediaSource.Local.Stream]   | Caller-supplied [MediaSource.Local.Stream.sizeHint].         |
 *  | [MediaSource.Local.Bytes]    | `bytes.size.toLong()` — always authoritative.                |
 *  | [AndroidUriMediaSource]      | [OpenableColumns.SIZE] primary, PFD `statSize` fallback.     |
 *  | [AndroidPfdMediaSource]      | [android.os.ParcelFileDescriptor.getStatSize] — `-1` → null. |
 *
 * Each platform branch is wrapped in `runCatching` so a SecurityException from a revoked URI
 * permission, a deleted file, or a `CursorIndexOutOfBoundsException` surfaces as a `null`
 * estimate (no progress) rather than aborting the compression. See the [estimateSourceSize]
 * contract.
 */
internal actual fun estimateSourceSize(input: MediaSource.Local): Long? = when (input) {
    is MediaSource.Local.FilePath -> filePathSize(input.path)
    is MediaSource.Local.Stream -> input.sizeHint
    is MediaSource.Local.Bytes -> input.bytes.size.toLong()
    is AndroidUriMediaSource -> uriSize(input)
    is AndroidPfdMediaSource -> pfdSize(input)
    else -> null
}

private fun filePathSize(path: String): Long? = runCatching {
    val file = File(path)
    if (file.exists()) file.length() else null
}.getOrNull()

/**
 * Primary path: `ContentResolver.query(uri, [OpenableColumns.SIZE], …)`. The column reports the
 * authoritative byte count for `content://` URIs backed by the Storage Access Framework,
 * MediaStore, and most `FileProvider` authorities without materialising the stream. Returns
 * `null` (not 0) when the column is missing, `NULL`, or `< 0` — a missing size is "unknown", not
 * "zero bytes".
 *
 * Fallback: `openFileDescriptor(uri, "r").statSize`. Covers `file://` URIs and `content://`
 * authorities that don't publish `OpenableColumns.SIZE` (e.g. some email attachment providers).
 * Only consulted when the primary returns `null`.
 */
private fun uriSize(input: AndroidUriMediaSource): Long? {
    val resolver = runCatching { KompressorContext.appContext.contentResolver }.getOrNull()
        ?: return null
    val viaColumn = runCatching {
        resolver.query(input.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx < 0 || cursor.isNull(idx)) null else cursor.getLong(idx).takeIf { it >= 0L }
            }
        }
    }.getOrNull()
    return viaColumn ?: runCatching {
        resolver.openFileDescriptor(input.uri, "r")?.use { it.statSize.takeIf { s -> s >= 0L } }
    }.getOrNull()
}

/**
 * `ParcelFileDescriptor.statSize` returns `-1` for FDs backed by pipes or sockets (streaming
 * content providers). Map `-1` → `null` so the probe contract ("negative is never returned")
 * stays honest.
 */
private fun pfdSize(input: AndroidPfdMediaSource): Long? = runCatching {
    input.pfd.statSize.takeIf { it >= 0L }
}.getOrNull()
