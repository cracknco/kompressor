/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.os.ParcelFileDescriptor
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.logging.SafeLogger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-side dispatch from the public [MediaSource] / [MediaDestination] contract into a
 * filesystem path the Android compressors' private `compressFilePath(inputPath, outputPath, ...)`
 * helpers can consume.
 *
 * Why a platform-specific helper rather than a single `commonMain` dispatcher? Because
 * [AndroidUriMediaSource] / [AndroidPfdMediaSource] / [AndroidUriMediaDestination] live in
 * `androidMain` as platform extensions and can't be referenced from `commonMain`.
 *
 * [toAndroidInputPath] is `suspend` — [MediaSource.Local.Stream] and [MediaSource.Local.Bytes]
 * inputs are materialized to a temp file via [resolveStreamOrBytesToTempFile] (CRA-95). The
 * [mediaType] parameter drives the OOM-warning path in that resolver ([MediaType.IMAGE] skips
 * the warn because the image compressors short-circuit Stream/Bytes before calling dispatch
 * — the branch here is reachable only when the compressor did not intercept the input).
 *
 * [toAndroidOutputHandle] stays synchronous. Stream outputs allocate a temp path and defer the
 * actual temp→sink copy to the commit step, which is suspending so progress ticks reach the
 * caller during [CompressionProgress.Phase.FINALIZING_OUTPUT].
 */

/** Result of resolving a [MediaSource] to an Android filesystem / URI string. */
internal class AndroidInputHandle(
    /** Path or `content://` URI string the private `compressFilePath(inputPath, outputPath, ...)` helper accepts. */
    val path: String,
    private val cleanupFn: () -> Unit,
) {
    /** Release temp resources (materialized temp file, owned PFD, closed Source). Idempotent. */
    fun cleanup() {
        cleanupFn()
    }
}

/**
 * Result of resolving a [MediaDestination] to an Android filesystem path.
 *
 * For `FilePath` destinations the compressor writes directly to [tempPath] and both [commit]
 * and [cleanup] are no-ops. For `AndroidUriMediaDestination` the compressor writes to a
 * throwaway [tempPath] and [commit] copies bytes into the URI. For
 * [MediaDestination.Local.Stream] [commit] streams the temp file into the consumer sink,
 * emitting progress ticks as it goes.
 */
internal class AndroidOutputHandle(
    /** Path the private `compressFilePath(inputPath, outputPath, ...)` helper writes to. */
    val tempPath: String,
    private val commitFn: suspend (suspend (Float) -> Unit) -> Unit,
    private val cleanupFn: () -> Unit,
) {
    /**
     * Publish the compressed bytes from [tempPath] to the caller's destination. No-op for
     * FilePath. For URI destinations the copy is unbuffered (fast path, no progress). For
     * Stream destinations the copy is chunked and [onProgress] is invoked with a fraction
     * in `[0.0, 1.0]` per 64 KB chunk.
     */
    suspend fun commit(onProgress: suspend (Float) -> Unit = {}) {
        commitFn(onProgress)
    }

    /** Release temp resources (temp file, buffered sink). Idempotent. */
    fun cleanup() {
        cleanupFn()
    }
}

/**
 * Resolve a [MediaSource] to an Android input path.
 *
 *  - [MediaSource.Local.FilePath] → direct path, no temp, no cleanup.
 *  - [AndroidUriMediaSource] → `uri.toString()` passed through; the private `compressFilePath`
 *    helpers accept `content://` URIs via their internal
 *    [android.content.ContentResolver] plumbing.
 *  - [AndroidPfdMediaSource] → materialized to a private temp file under the app cache dir;
 *    PFD is closed on cleanup.
 *  - [MediaSource.Local.Stream] / [MediaSource.Local.Bytes] → materialized via
 *    [resolveStreamOrBytesToTempFile]. The resolver emits a WARN via [logger] when Bytes is
 *    used for a non-image [mediaType] and exceeds [BYTES_WARN_THRESHOLD]. The temp file is
 *    deleted — and the Stream source is closed when `closeOnFinish = true` — on cleanup.
 *  - Everything else (cross-platform wrapper slipped in) → fail loudly.
 */
internal suspend fun MediaSource.toAndroidInputPath(
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit = {},
): AndroidInputHandle = when (this) {
    is MediaSource.Local.FilePath -> AndroidInputHandle(path, cleanupFn = {})
    is AndroidUriMediaSource -> AndroidInputHandle(uri.toString(), cleanupFn = {})
    is AndroidPfdMediaSource -> materializePfdHandle(pfd)
    // Split into two `is` arms rather than `is A, is B` so the smart-cast narrows to the
    // specific subtype at each call site — the `MediaSource.Local` parameter on
    // [materializeStreamOrBytes] accepts either without a cast.
    is MediaSource.Local.Stream -> materializeStreamOrBytes(this, mediaType, logger, onProgress)
    is MediaSource.Local.Bytes -> materializeStreamOrBytes(this, mediaType, logger, onProgress)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaSource subtype on Android: ${this::class.simpleName}",
    )
}

/**
 * Resolve a [MediaDestination] to an Android output path.
 *
 *  - [MediaDestination.Local.FilePath] → direct path, no temp, no commit.
 *  - [AndroidUriMediaDestination] → write to private temp, commit copies bytes into the URI.
 *  - [MediaDestination.Local.Stream] → write to private temp, commit streams temp → sink in
 *    64 KB chunks and emits `FINALIZING_OUTPUT` progress through the caller's [onProgress].
 */
internal fun MediaDestination.toAndroidOutputHandle(): AndroidOutputHandle = when (this) {
    is MediaDestination.Local.FilePath -> AndroidOutputHandle(
        tempPath = path,
        commitFn = { _ -> },
        cleanupFn = {},
    )
    is AndroidUriMediaDestination -> androidUriOutputHandle(this)
    is MediaDestination.Local.Stream -> streamOutputHandle(this)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaDestination subtype on Android: ${this::class.simpleName}",
    )
}

private suspend fun materializeStreamOrBytes(
    input: MediaSource.Local,
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit,
): AndroidInputHandle {
    val resolved = resolveStreamOrBytesToTempFile(
        input = input,
        tempDir = kompressorTempDir(),
        mediaType = mediaType,
        logger = logger,
        onProgress = onProgress,
    )
    return AndroidInputHandle(path = resolved.path, cleanupFn = resolved.cleanup)
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
        // URI commit is a synchronous `ContentResolver.openOutputStream` copy. No progress
        // ticks — the caller's `FINALIZING_OUTPUT, 1f` canonical terminal still fires from
        // the compressor body once `commit` returns.
        //
        // `withContext(Dispatchers.IO)` keeps structured concurrency intact when the public
        // `compress()` entry is invoked from `Dispatchers.Default` or `Dispatchers.Main` —
        // the FileInputStream + openOutputStream + copyTo chain would otherwise block a
        // non-IO pool thread. Matches the pattern used in `AndroidAudioCompressor:217`,
        // `AndroidVideoCompressor:188`, and `AndroidKompressor:35`.
        commitFn = { _ -> withContext(Dispatchers.IO) { copyTempToUri(tempFile, dest) } },
        cleanupFn = { runCatching { tempFile.delete() } },
    )
}

private fun streamOutputHandle(dest: MediaDestination.Local.Stream): AndroidOutputHandle {
    val outSink = createStreamOutputSink(dest, kompressorTempDir())
    return AndroidOutputHandle(
        tempPath = outSink.tempPath,
        commitFn = { onProgress -> outSink.publish(onProgress) },
        cleanupFn = { outSink.cleanup() },
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
