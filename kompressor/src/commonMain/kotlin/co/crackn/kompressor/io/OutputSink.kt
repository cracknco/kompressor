/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.buffer
import okio.use

/**
 * Chunk size for the temp-file → [okio.Sink] copy performed by [StreamOutputSink.publish].
 * 64 KB for the same reason [materializeToTempFile] uses 64 KB — amortises syscall cost on
 * mobile storage while keeping the resident heap bounded independent of output size.
 */
private const val SINK_COPY_BUFFER_SIZE: Long = 64L * 1024L

/**
 * Abstraction over a compression destination used by the audio / video compressors on both
 * platforms. Introduced by CRA-95 so the "compressor writes to a filesystem path, then
 * optionally copies that path into a consumer-supplied [okio.Sink]" shape lives in one place.
 *
 *  - [FileOutputSink] — [tempPath] IS the caller-supplied destination; [publish] is a no-op.
 *    Used for [MediaDestination.Local.FilePath] and platform URI destinations where the
 *    platform dispatch has its own commit step (MediaStore `IS_PENDING` release, etc.).
 *  - [StreamOutputSink] — [tempPath] points at an internal temp file; [publish] streams
 *    chunks from the temp file into the consumer's [okio.Sink] and emits progress under
 *    [CompressionProgress.Phase.FINALIZING_OUTPUT].
 *
 * Image compressors do not use this abstraction — they short-circuit [MediaDestination.Local.Stream]
 * by encoding the [android.graphics.Bitmap] / `UIImage` directly into the consumer sink,
 * avoiding the temp-file hop entirely.
 */
internal sealed interface OutputSink {
    /** Path the platform compressor writes to (real destination for FilePath, temp file for Stream). */
    val tempPath: String

    /**
     * Publish the compressed bytes to the consumer destination. No-op for [FileOutputSink];
     * for [StreamOutputSink] this copies [tempPath] into the [okio.Sink] in chunks, calling
     * [onProgress] at each chunk boundary with a fraction in `[0.0, 1.0]`.
     */
    suspend fun publish(onProgress: suspend (Float) -> Unit = {})

    /** Release temp resources. Idempotent — safe to invoke from both success and failure paths. */
    fun cleanup()
}

/**
 * File-backed destination — the compressor writes directly to [tempPath], which is the
 * caller's requested file. [publish] and [cleanup] are no-ops. Exists so the common call
 * site in a compressor (`outputSink.tempPath` / `outputSink.publish(...)` / `outputSink.cleanup()`)
 * is uniform whether the destination is a file or a stream.
 */
internal class FileOutputSink(override val tempPath: String) : OutputSink {
    override suspend fun publish(onProgress: suspend (Float) -> Unit) = Unit
    override fun cleanup() = Unit
}

/**
 * Stream-backed destination — the compressor writes to [tempFile]; [publish] copies the
 * bytes from [tempFile] into the consumer [sink]. Used by the audio / video compressors
 * because Media3 `Transformer` (Android) and `AVAssetWriter` / `AVAssetExportSession`
 * (iOS) both require a seekable local file for their muxer output; streaming straight into
 * an [okio.Sink] is not an option.
 *
 * **Sink ownership:** [cleanup] closes [sink] only when [closeOnFinish] is `true` (the
 * default at the [MediaDestination.Local.Stream] construction site). Call sites with
 * `closeOnFinish = false` must flush but not close — [publish] therefore flushes the
 * buffered wrapper explicitly rather than relying on `use { }`, which would close the
 * underlying consumer sink as a side effect.
 *
 * @property tempFile Materialized temp file the compressor wrote into.
 * @property sink Consumer sink receiving the final bytes.
 * @property closeOnFinish If `true`, [cleanup] closes [sink] in addition to deleting the
 *   temp file.
 */
internal class StreamOutputSink(
    private val tempFile: Path,
    private val sink: Sink,
    private val closeOnFinish: Boolean,
    private val fileSystem: FileSystem = kompressorFileSystem,
) : OutputSink {
    override val tempPath: String = tempFile.toString()

    override suspend fun publish(onProgress: suspend (Float) -> Unit) {
        // Best-effort total — if metadata lookup fails (filesystem implementations without
        // size support) we still copy; progress simply stays at 0 until the copy completes.
        val totalBytes = runCatching { fileSystem.metadata(tempFile).size }.getOrNull() ?: 0L
        copyTempToSink(totalBytes, onProgress)
    }

    private suspend fun copyTempToSink(totalBytes: Long?, onProgress: suspend (Float) -> Unit) {
        val bufferedSink = sink.buffer()
        var copied = 0L
        fileSystem.source(tempFile).use { source ->
            val scratch = Buffer()
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(scratch, SINK_COPY_BUFFER_SIZE)
                if (read == -1L) break
                bufferedSink.write(scratch, read)
                bufferedSink.emitCompleteSegments()
                copied += read
                onProgress(computeFraction(copied, totalBytes))
            }
        }
        // Flush (not close): consumer sink lifecycle is governed by [closeOnFinish] in
        // [cleanup]. Closing the buffered wrapper would propagate a close() to the consumer
        // sink and break the `closeOnFinish = false` contract.
        bufferedSink.flush()
    }

    override fun cleanup() {
        runCatching { fileSystem.delete(tempFile, mustExist = false) }
        if (closeOnFinish) runCatching { sink.close() }
    }

    private fun computeFraction(copied: Long, totalBytes: Long?): Float =
        if (totalBytes != null && totalBytes > 0L) {
            (copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Build a [StreamOutputSink] for [destination] placing the temp file under [tempDir]. The
 * temp file is **not** pre-created — `AVAssetExportSession` (iOS) refuses to write to an
 * existing path, and Media3 `Transformer` (Android) is happy to create the file itself —
 * so the factory just reserves the name and ensures the parent directory exists.
 */
internal fun createStreamOutputSink(
    destination: MediaDestination.Local.Stream,
    tempDir: Path,
): StreamOutputSink = createStreamOutputSink(
    destination = destination,
    fileSystem = kompressorFileSystem,
    tempDir = tempDir,
)

/**
 * Test-injection overload — identical contract to the primary [createStreamOutputSink] but
 * routes temp-file creation / deletion through an explicitly-provided [FileSystem] (typically
 * an `okio.fakefilesystem.FakeFileSystem` in commonTest).
 */
internal fun createStreamOutputSink(
    destination: MediaDestination.Local.Stream,
    fileSystem: FileSystem,
    tempDir: Path,
): StreamOutputSink {
    if (!fileSystem.exists(tempDir)) fileSystem.createDirectories(tempDir)
    val tempFile = tempDir / "kmp_out_${randomMaterializationId()}.bin"
    return StreamOutputSink(tempFile, destination.sink, destination.closeOnFinish, fileSystem)
}
