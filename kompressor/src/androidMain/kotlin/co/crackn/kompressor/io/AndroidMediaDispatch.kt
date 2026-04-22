/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.os.ParcelFileDescriptor
import co.crackn.kompressor.KompressorContext
import java.io.File
import java.util.UUID

/**
 * Android-side dispatch from the public [MediaSource] / [MediaDestination] contract into a
 * filesystem path the legacy `compress(inputPath, outputPath, ...)` overloads can consume.
 *
 * Why a separate helper rather than extending the common
 * [co.crackn.kompressor.io.requireFilePathOrThrow] in `commonMain`? Because the common helper
 * cannot reference [AndroidUriMediaSource] / [AndroidPfdMediaSource] /
 * [AndroidUriMediaDestination] — those live in `androidMain` as platform extensions. The
 * Android compressors' `compress(MediaSource, MediaDestination, ...)` overloads call the
 * Android-specific helpers here; `commonMain` variants they cannot resolve fall through to the
 * common [co.crackn.kompressor.io.requireFilePathOrThrow] which raises the canonical
 * "CRA-95" `UnsupportedOperationException`.
 *
 * Resolution results come back wrapped in [AndroidInputHandle] / [AndroidOutputHandle] so the
 * compressor's `finally` block can run the correct cleanup — temp file deletion, PFD close,
 * post-compression URI commit with `IS_PENDING` release — without the compressor body having to
 * re-branch on the input / output type.
 */

/** Result of resolving a [MediaSource] to an Android filesystem / URI string. */
internal class AndroidInputHandle(
    /** Path or `content://` URI string the legacy compress(String, String, ...) overload accepts. */
    val path: String,
    private val cleanupFn: () -> Unit,
) {
    /** Release temp resources (materialized temp file, owned PFD). Idempotent. */
    fun cleanup() {
        cleanupFn()
    }
}

/**
 * Result of resolving a [MediaDestination] to an Android filesystem path.
 *
 * For `FilePath` destinations the compressor writes directly to [tempPath] (which IS the caller's
 * requested path) and both [commit] and [cleanup] are no-ops. For `AndroidUriMediaDestination` the
 * compressor writes to a throwaway [tempPath], then [commit] copies the bytes into the URI via
 * `ContentResolver.openOutputStream(...)` (through [MediaStoreOutputStrategy] when the authority
 * is `"media"`), and [cleanup] deletes the temp file whether commit succeeded or not.
 */
internal class AndroidOutputHandle(
    /** Path the legacy compress(String, String, ...) overload writes to. */
    val tempPath: String,
    private val commitFn: () -> Unit,
    private val cleanupFn: () -> Unit,
) {
    /** Publish the compressed bytes from [tempPath] to the caller's destination. No-op for FilePath. */
    fun commit() {
        commitFn()
    }

    /** Release temp resources (temp file). Idempotent. */
    fun cleanup() {
        cleanupFn()
    }
}

/**
 * Resolve a [MediaSource] to an Android input path.
 *
 *  - [MediaSource.Local.FilePath] → direct path, no temp file, no cleanup.
 *  - [AndroidUriMediaSource] → `uri.toString()` passed through. The legacy compressors already
 *    handle `content://` / `file://` URIs via their internal sealed `ImageSource` /
 *    `toMediaItemUri` / `resolveMediaInputSize` plumbing, so no materialization is needed.
 *  - [AndroidPfdMediaSource] → materialized to a private temp file under the app cache dir.
 *    The PFD is closed on [AndroidInputHandle.cleanup] per the builder's `closeOnFinish = true`
 *    contract.
 *  - [MediaSource.Local.Stream] / [MediaSource.Local.Bytes] → throws `UnsupportedOperationException`
 *    pointing at CRA-95 (the ticket that will wire Stream/Bytes through [TempFileMaterializer]).
 */
internal fun MediaSource.toAndroidInputPath(): AndroidInputHandle = when (this) {
    is MediaSource.Local.FilePath -> AndroidInputHandle(path, cleanupFn = {})
    is AndroidUriMediaSource -> AndroidInputHandle(uri.toString(), cleanupFn = {})
    is AndroidPfdMediaSource -> materializePfdHandle(pfd)
    is MediaSource.Local.Stream -> throw UnsupportedOperationException(STREAM_INPUT_MSG)
    is MediaSource.Local.Bytes -> throw UnsupportedOperationException(BYTES_INPUT_MSG)
    // [MediaSource.Local] is not sealed — a future iOS-specific wrapper (e.g. `IosPHAssetMediaSource`,
    // T5) would land here if misused on Android. Fail loudly with the wrapper class name so the
    // caller sees which builder was cross-wired.
    else -> throw UnsupportedOperationException(
        "Unsupported MediaSource subtype on Android: ${this::class.simpleName}",
    )
}

/**
 * Resolve a [MediaDestination] to an Android output path.
 *
 *  - [MediaDestination.Local.FilePath] → direct path, no temp, no commit.
 *  - [AndroidUriMediaDestination] → write to a private temp file, then on commit copy the bytes
 *    into the URI. MediaStore URIs go through [MediaStoreOutputStrategy] (which handles the
 *    `IS_PENDING` flag and gracefully degrades on custom providers); everything else uses
 *    `ContentResolver.openOutputStream(uri)` directly.
 *  - [MediaDestination.Local.Stream] → throws `UnsupportedOperationException` pointing at CRA-95.
 */
internal fun MediaDestination.toAndroidOutputHandle(): AndroidOutputHandle = when (this) {
    is MediaDestination.Local.FilePath -> AndroidOutputHandle(
        tempPath = path,
        commitFn = {},
        cleanupFn = {},
    )
    is AndroidUriMediaDestination -> androidUriOutputHandle(this)
    is MediaDestination.Local.Stream -> throw UnsupportedOperationException(STREAM_OUTPUT_MSG)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaDestination subtype on Android: ${this::class.simpleName}",
    )
}

private fun materializePfdHandle(pfd: ParcelFileDescriptor): AndroidInputHandle {
    val tempDir = androidKompressorTempDir().apply { mkdirs() }
    val tempFile = File(tempDir, "kmp_pfd_${UUID.randomUUID()}.bin")
    // ParcelFileDescriptor.AutoCloseInputStream closes the PFD when the stream closes;
    // we want the opposite — materialize then keep the PFD alive for explicit close on
    // cleanup — so use the non-auto-close FileInputStream variant.
    //
    // Broadened to `Throwable` (from IOException) so OOM / VirtualMachineError on a large PFD
    // still release the tempFile + PFD before propagating — previously the narrower catch
    // leaked both handles on non-IOException failures. Rethrow to preserve the original error
    // type for the caller (PR #141 review, finding #4).
    @Suppress("TooGenericExceptionCaught")
    try {
        java.io.FileInputStream(pfd.fileDescriptor).use { input ->
            java.io.FileOutputStream(tempFile).use { out ->
                input.copyTo(out)
            }
        }
    } catch (t: Throwable) {
        runCatching { tempFile.delete() }
        runCatching { pfd.close() }
        throw t
    }
    return AndroidInputHandle(
        path = tempFile.absolutePath,
        cleanupFn = {
            runCatching { tempFile.delete() }
            runCatching { pfd.close() }
        },
    )
}

private fun androidUriOutputHandle(dest: AndroidUriMediaDestination): AndroidOutputHandle {
    val tempDir = androidKompressorTempDir().apply { mkdirs() }
    val tempFile = File(tempDir, "kmp_uri_out_${UUID.randomUUID()}.bin")
    return AndroidOutputHandle(
        tempPath = tempFile.absolutePath,
        commitFn = { copyTempToUri(tempFile, dest) },
        cleanupFn = { runCatching { tempFile.delete() } },
    )
}

private fun copyTempToUri(tempFile: File, dest: AndroidUriMediaDestination) {
    val resolver = KompressorContext.appContext.contentResolver
    if (dest.isMediaStoreUri) {
        // IS_PENDING stays at 1 if the copy throws mid-stream — the half-written file never
        // becomes gallery-visible. Only a clean `copyTo` + flush + close clears it to 0. See
        // [MediaStoreOutputStrategy.withWriteStream] KDoc for the rollback contract.
        MediaStoreOutputStrategy.withWriteStream(resolver, dest.uri) { sink ->
            java.io.FileInputStream(tempFile).use { src ->
                src.copyTo(sink)
            }
        }
    } else {
        // `error()` is the detekt-approved way to raise IllegalStateException (see
        // config/detekt/detekt.yml UseCheckOrError).
        val output = resolver.openOutputStream(dest.uri)
            ?: error("ContentResolver returned null OutputStream for ${dest.uri}")
        output.use { sink ->
            java.io.FileInputStream(tempFile).use { src ->
                src.copyTo(sink)
            }
        }
    }
}

/**
 * Private temp directory for Android-side materialization (PFD input, URI output). Kept alongside
 * the existing `kompressor-io` tree used by [co.crackn.kompressor.io.materializeToTempFile] so all
 * Kompressor-owned temp files live under one sub-tree and can be swept together.
 */
private fun androidKompressorTempDir(): File =
    File(KompressorContext.appContext.cacheDir, "kompressor-io")

// --- Error messages mirror commonMain/FilePathDispatch.kt so the legacy and Android-aware ---
// paths throw byte-identical strings. Keep these in sync if commonMain's messages change.

private const val STREAM_INPUT_MSG: String =
    "MediaSource.Local.Stream input will be supported in CRA-95. " +
        "For now, use MediaSource.Local.FilePath."
private const val BYTES_INPUT_MSG: String =
    "MediaSource.Local.Bytes input will be supported in CRA-95. " +
        "For now, use MediaSource.Local.FilePath."
private const val STREAM_OUTPUT_MSG: String =
    "MediaDestination.Local.Stream output will be supported in CRA-95. " +
        "For now, use MediaDestination.Local.FilePath."
