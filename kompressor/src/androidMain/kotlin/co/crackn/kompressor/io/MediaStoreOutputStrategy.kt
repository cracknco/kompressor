/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.content.ContentResolver
import android.content.ContentValues
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.MediaStore
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import java.io.OutputStream

/**
 * Encapsulates the MediaStore `IS_PENDING` pattern introduced in Android 10 (scoped storage).
 *
 * **Contract** — a MediaStore URI inserted with `contentResolver.insert(...)` is created in a
 * "pending" state that is invisible to `MediaStore.Files.getContentUri(...)` queries until the
 * owning process either:
 *  1. sets `IS_PENDING=0` via `contentResolver.update(...)`, or
 *  2. calls `contentResolver.delete(uri, ...)` to drop the entry entirely.
 *
 * Kompressor performs the `IS_PENDING=1` → write → `IS_PENDING=0` dance on behalf of the caller
 * so downstream code sees the same file-visible-after-compression behaviour whether the
 * destination is a plain filesystem path or a MediaStore URI.
 *
 * **Usage** —
 * ```
 * MediaStoreOutputStrategy.withWriteStream(contentResolver, uri) { output ->
 *     output.write(compressedBytes)
 * }
 * // IS_PENDING is cleared ONLY if the block returned normally; a throw from the block
 * // leaves the entry at IS_PENDING=1 so it stays invisible to gallery pickers until the
 * // caller either retries or drops the URI.
 * ```
 *
 * **Failure semantics** — if the write block throws, the strategy does NOT clear `IS_PENDING`.
 * The URI stays pending/invisible and the caller can drop it via `contentResolver.delete(uri)`.
 * This protects gallery integrity: a half-written file is never gallery-visible on a failure
 * path. See `tmp/tier1-1-io-model.md §14 R3` and PR #141 review (2026-04-22).
 *
 * **Graceful fallback** — a custom `ContentProvider` that aliases [MediaStore.AUTHORITY] without
 * implementing the `IS_PENDING` contract will reject the `update(...)` calls with
 * [SQLiteException], [IllegalArgumentException], or [SecurityException]. In that case we log a
 * WARN via the injected [SafeLogger] and continue: the compressed file is still written
 * correctly via the provided [OutputStream], only the pending flag could not be toggled.
 *
 * **Orphan rollback** — if [ContentResolver.openOutputStream] throws _after_ [markPending]
 * succeeded (e.g. `FileNotFoundException` from a provider that revoked access between insert
 * and open), the entry would otherwise be orphaned at `IS_PENDING=1` forever — invisible to
 * the gallery and with no reference for the caller to clean up. [withWriteStream] catches this
 * case and issues a best-effort [ContentResolver.delete] to drop the row before rethrowing.
 *
 * Logging is routed through [SafeLogger] rather than [android.util.Log] directly so the library's
 * "single entry point for log emission" rule (`scripts/check-no-raw-logging.sh`) stays intact.
 * Callers without a logger pass `SafeLogger(NoOpLogger)`.
 */
internal object MediaStoreOutputStrategy {

    /**
     * Run [block] against an [OutputStream] opened for writing to a MediaStore [uri]. Sets
     * `IS_PENDING=1` before invoking [block]; clears it to `0` only if [block] returns
     * normally.
     *
     * `IS_PENDING` toggle failures are caught narrowly — [SQLiteException] (raised by the real
     * `MediaProvider` when the URI's row is missing or unwritable), [IllegalArgumentException]
     * (raised by custom providers that flat-out reject the column), and [SecurityException]
     * (raised when the caller lacks `WRITE_EXTERNAL_STORAGE` under pre-Q or SAF permission under
     * Q+). Anything else — [OutOfMemoryError], [RuntimeException] subclasses from a broken
     * provider — propagates so a genuine bug surfaces instead of being swallowed.
     *
     * If [ContentResolver.openOutputStream] fails (returns null OR throws), [markPending]'s
     * `IS_PENDING=1` is rolled back with a best-effort [ContentResolver.delete] so the row
     * doesn't orphan. The original open failure propagates.
     *
     * @throws IllegalStateException if [ContentResolver.openOutputStream] returns `null`.
     */
    @Suppress("TooGenericExceptionCaught")
    fun <T> withWriteStream(
        resolver: ContentResolver,
        uri: Uri,
        logger: SafeLogger = SafeLogger(NoOpLogger),
        block: (OutputStream) -> T,
    ): T {
        markPending(resolver, uri, logger)

        val raw = try {
            resolver.openOutputStream(uri)
                ?: error("ContentResolver returned null OutputStream for $uri")
        } catch (t: Throwable) {
            // markPending already wrote IS_PENDING=1 but we have no stream to write — without
            // rollback the row would orphan invisibly. Best-effort delete(uri); any failure of
            // the rollback is swallowed so the caller sees the original open failure.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        val result = raw.use(block)
        // Only reached if block returned normally AND raw.close() succeeded. A throw from either
        // path propagates through `use`'s finally (closing raw) without touching IS_PENDING —
        // the entry stays at IS_PENDING=1, invisible, for the caller to clean up.
        clearPending(resolver, uri, logger)
        return result
    }

    private fun markPending(resolver: ContentResolver, uri: Uri, logger: SafeLogger) {
        try {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) },
                null,
                null,
            )
        } catch (e: SQLiteException) {
            logger.warn(LogTags.IO) {
                "Could not set IS_PENDING=1 on $uri (provider may not support it): ${e.message}"
            }
        } catch (e: IllegalArgumentException) {
            logger.warn(LogTags.IO) {
                "Could not set IS_PENDING=1 on $uri (provider rejected column): ${e.message}"
            }
        } catch (e: SecurityException) {
            logger.warn(LogTags.IO) {
                "Could not set IS_PENDING=1 on $uri (permission denied): ${e.message}"
            }
        }
    }

    private fun clearPending(resolver: ContentResolver, uri: Uri, logger: SafeLogger) {
        try {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: SQLiteException) {
            logger.warn(LogTags.IO) { "IS_PENDING release failed on $uri, output is written: ${e.message}" }
        } catch (e: IllegalArgumentException) {
            logger.warn(LogTags.IO) { "IS_PENDING release failed on $uri, output is written: ${e.message}" }
        } catch (e: SecurityException) {
            logger.warn(LogTags.IO) { "IS_PENDING release failed on $uri, output is written: ${e.message}" }
        }
    }
}
