/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use

/**
 * Chunk size for materialization — 64 KB. Small enough to keep the heap footprint
 * constant on streams of arbitrary size, large enough to amortise per-syscall cost on
 * mobile storage (8 KB produces excess syscalls on multi-GB streams; 1 MB forfeits
 * the "heap stays small" property). Value pinned by `tmp/tier1-1-io-model.md` §3.2.
 */
private const val BUFFER_SIZE: Long = 64L * 1024L

/**
 * Materialize this okio [Source] to a temp file under [tempDir], copying chunks of
 * [BUFFER_SIZE] bytes so the resident heap stays O([BUFFER_SIZE]) regardless of source
 * size — even multi-GB streams materialize without OOM risk.
 *
 * Consumed by the CRA-89 I/O refactor in T6 to route `MediaSource.Local.Stream` /
 * `MediaSource.Local.Bytes` into the video/audio pipelines (Media3 `Transformer` and
 * AVFoundation both require seekable local files). This primitive intentionally does
 * not implement the "images skip the temp file" short-circuit — image decoding can be
 * fed directly from a buffered source by the image compressor itself.
 *
 * **Cancellation:** [currentCoroutineContext] is queried on every chunk boundary and
 * [kotlinx.coroutines.ensureActive] rethrows a [CancellationException] as soon as the
 * calling scope is cancelled. The partial temp file is deleted before the exception
 * propagates, so a cancelled materialization leaves no artefact on disk.
 *
 * **Source ownership:** the [Source] receiver is **not** closed by this function.
 * Callers own the source lifecycle — see `MediaSource.Local.Stream.closeOnFinish` for
 * the consumer-facing contract. Closing a buffered wrapper around a live stream that
 * the caller still intends to reuse would silently break the `closeOnFinish = false`
 * path introduced in CRA-90.
 *
 * **Progress callback:** when [sizeHint] is non-null, [onProgress] fires at each chunk
 * boundary with a monotonically non-decreasing fraction in `[0.0, 1.0]` computed as
 * `bytesWritten / sizeHint` (clamped to the valid range to survive a [sizeHint] that
 * underestimates the real source size). When [sizeHint] is `null`, [onProgress] is still
 * invoked at every chunk but with a constant `0f` — callers that want a "something is
 * happening" heartbeat can count invocations; callers that want a real bar should supply
 * a size hint. Matches the transparency-first design in `tmp/tier1-1-io-model.md` §5.2.
 *
 * **Error handling:** any [Throwable] raised during copy (read failure, disk full,
 * permission denied) triggers a best-effort `deleteIfExists(tempFile)` before rethrowing
 * the original exception — callers never see a partial temp file on the error path.
 *
 * @param tempDir Directory where the temp file is created. Created (with parents) if
 *   missing. Caller is responsible for choosing a writable location — see
 *   `kompressorTempDir()` for the canonical per-platform default.
 * @param sizeHint Optional total byte count of this source. Only affects progress
 *   reporting; the copy itself is driven by EOF, not by the hint.
 * @param onProgress Suspending progress callback. Called at each [BUFFER_SIZE]-byte
 *   boundary with a fraction in `[0.0, 1.0]` (when [sizeHint] is provided) or `0f`
 *   (when [sizeHint] is `null`).
 *
 * @return Absolute [Path] of the created temp file. The caller owns it — typical pattern
 *   is `try { … } finally { FileSystem.SYSTEM.delete(tempFile) }`.
 *
 * @throws okio.IOException on read / write / filesystem failures (wrapped by okio).
 * @throws CancellationException if the calling coroutine scope is cancelled during copy;
 *   the temp file is deleted before the exception propagates.
 */
internal suspend fun Source.materializeToTempFile(
    tempDir: Path,
    sizeHint: Long? = null,
    onProgress: suspend (fraction: Float) -> Unit = {},
): Path = materializeToTempFile(
    fileSystem = kompressorFileSystem,
    tempDir = tempDir,
    sizeHint = sizeHint,
    onProgress = onProgress,
)

/**
 * Test-injection overload — same contract as the primary [materializeToTempFile] but
 * lets commonTest exercise the copy loop against an `okio.fakefilesystem.FakeFileSystem`
 * (or any other [FileSystem] implementation) without touching real disk. Kept `internal`
 * so the public surface stays limited to the no-[FileSystem] overload.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun Source.materializeToTempFile(
    fileSystem: FileSystem,
    tempDir: Path,
    sizeHint: Long? = null,
    onProgress: suspend (fraction: Float) -> Unit = {},
): Path {
    if (!fileSystem.exists(tempDir)) fileSystem.createDirectories(tempDir)

    val tempFile = tempDir / "kmp_io_${randomMaterializationId()}.bin"

    try {
        copyChunksTo(fileSystem, tempFile, sizeHint, onProgress)
        return tempFile
    } catch (cancel: CancellationException) {
        runCatching { fileSystem.delete(tempFile, mustExist = false) }
        throw cancel
    } catch (t: Throwable) {
        runCatching { fileSystem.delete(tempFile, mustExist = false) }
        throw t
    }
}

/**
 * Drive the chunked copy loop. Extracted from [materializeToTempFile] to keep the public
 * wrapper's cyclomatic complexity and length under the Detekt thresholds — the
 * try/catch/delete concerns live above, the actual copy lives here.
 *
 * The receiver [Source] is read directly (no `.buffer()` wrapper) because buffering +
 * `.use { }` would close the source on exit, violating the "caller owns source lifecycle"
 * contract documented on [materializeToTempFile].
 *
 * Uses [okio.BufferedSink.emitCompleteSegments] rather than [okio.BufferedSink.emit] per
 * chunk so writes are coalesced to okio's 8 KB segment boundaries — ~8× fewer `write()`
 * syscalls than `emit()` on a 1 MB stream. The final partial segment is flushed by
 * `use { }` closing the sink at loop exit.
 */
private suspend fun Source.copyChunksTo(
    fileSystem: FileSystem,
    tempFile: Path,
    sizeHint: Long?,
    onProgress: suspend (fraction: Float) -> Unit,
) {
    var bytesWritten = 0L
    fileSystem.sink(tempFile).buffer().use { sink ->
        val readBuffer: Buffer = sink.buffer
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = this.read(readBuffer, BUFFER_SIZE)
            if (read == -1L) break
            sink.emitCompleteSegments()
            bytesWritten += read
            onProgress(computeFraction(bytesWritten, sizeHint))
        }
    }
}

/**
 * Compute the progress fraction. Extracted so the elvis + clamp logic doesn't inflate
 * the copy loop's cognitive complexity.
 */
private fun computeFraction(bytesWritten: Long, sizeHint: Long?): Float =
    if (sizeHint != null && sizeHint > 0L) {
        (bytesWritten.toFloat() / sizeHint.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

/**
 * Short random identifier used to name the temp file (`kmp_io_<id>.bin`). Resolved per
 * platform so we don't have to propagate `@OptIn(ExperimentalUuidApi::class)` across the
 * library: Android uses `java.util.UUID.randomUUID()`, iOS uses `NSUUID.UUID().UUIDString`.
 * Both deliver >122 bits of entropy — collision on a single-process temp dir is not a
 * concern.
 */
internal expect fun randomMaterializationId(): String

/**
 * Platform-specific pointer to `FileSystem.SYSTEM`. okio declares `SYSTEM` in its
 * `systemFileSystemMain` source set (JVM + Native both inherit from it) but **not** in
 * `commonMain`, so a direct `FileSystem.SYSTEM` reference from our commonMain fails to
 * compile. This `expect` / `actual` bridge is the minimal indirection: each platform's
 * actual simply returns `FileSystem.SYSTEM`.
 */
internal expect val kompressorFileSystem: FileSystem
