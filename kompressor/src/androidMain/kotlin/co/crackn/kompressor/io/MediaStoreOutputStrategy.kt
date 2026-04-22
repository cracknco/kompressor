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
 * MediaStoreOutputStrategy.openForWrite(contentResolver, uri).use { output ->
 *     output.write(compressedBytes)
 * }
 * // IS_PENDING is cleared automatically on close (success path).
 * ```
 *
 * **Failure semantics** — on a failure during write the strategy does NOT delete the MediaStore
 * entry: the URI points to a half-written file that the caller can drop via
 * `contentResolver.delete(uri)` if they want to clean up. This mirrors `deletingOutputOnFailure`
 * semantics on file paths and gives the caller full control of the retry vs. abort decision.
 *
 * **Graceful fallback** — a custom `ContentProvider` that aliases [MediaStore.AUTHORITY] without
 * implementing the `IS_PENDING` contract will reject the `update(...)` calls with
 * [SQLiteException] or [IllegalArgumentException]. In that case we log a WARN via the injected
 * [SafeLogger] and continue: the compressed file is still written correctly via the returned
 * [OutputStream], only the pending flag could not be toggled. This tracks
 * `tmp/tier1-1-io-model.md §14 R3`.
 *
 * Logging is routed through [SafeLogger] rather than [android.util.Log] directly so the library's
 * "single entry point for log emission" rule (`scripts/check-no-raw-logging.sh`) stays intact.
 * Callers without a logger pass `SafeLogger(NoOpLogger)`.
 */
internal object MediaStoreOutputStrategy {

    /**
     * Open an [OutputStream] for writing to a MediaStore [uri]. Sets `IS_PENDING=1` before
     * returning; the returned stream clears `IS_PENDING=0` when closed on success.
     *
     * `IS_PENDING` toggle failures are caught narrowly — [SQLiteException] (raised by the real
     * `MediaProvider` when the URI's row is missing or unwritable), [IllegalArgumentException]
     * (raised by custom providers that flat-out reject the column), and [SecurityException]
     * (raised when the caller lacks `WRITE_EXTERNAL_STORAGE` under pre-Q or SAF permission under
     * Q+). Anything else — [OutOfMemoryError], [RuntimeException] subclasses from a broken
     * provider — propagates so a genuine bug surfaces instead of being swallowed.
     *
     * @throws IllegalStateException if [ContentResolver.openOutputStream] returns `null`, which
     *   means the provider explicitly refused to open the URI for writing — not a bug, but
     *   also not something the caller can recover from transparently.
     */
    fun openForWrite(
        resolver: ContentResolver,
        uri: Uri,
        logger: SafeLogger = SafeLogger(NoOpLogger),
    ): OutputStream {
        markPending(resolver, uri, logger)

        val raw = resolver.openOutputStream(uri)
            ?: error("ContentResolver returned null OutputStream for $uri")

        return ReleasingOutputStream(raw, resolver, uri, logger)
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

    /**
     * Output stream that forwards writes verbatim to [delegate] and clears the `IS_PENDING` flag
     * exactly once on [close]. Extracted from the [openForWrite] body so the `close()` path is
     * idempotent (calling close twice — common with try-with-resources wrappers — does not
     * re-run the `IS_PENDING=0` update).
     *
     * The same narrow exception set ([SQLiteException] / [IllegalArgumentException] /
     * [SecurityException]) is caught here as in [markPending] — a custom provider that rejected
     * the `IS_PENDING=1` set will also typically reject the `IS_PENDING=0` clear, and we
     * maintain the same log-and-continue contract in both directions. The compressed file has
     * already been flushed to storage by the time [close] runs, so a failed clear never loses
     * user data.
     */
    private class ReleasingOutputStream(
        private val delegate: OutputStream,
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val logger: SafeLogger,
    ) : OutputStream() {
        private var closed: Boolean = false

        override fun write(b: Int) { delegate.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len) }
        override fun write(b: ByteArray) { delegate.write(b) }
        override fun flush() { delegate.flush() }

        override fun close() {
            if (closed) return
            closed = true
            delegate.close()
            clearPending()
        }

        private fun clearPending() {
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
}
