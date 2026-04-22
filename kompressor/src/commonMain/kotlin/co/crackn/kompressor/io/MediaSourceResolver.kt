/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.SafeLogger
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * Size at which Kompressor emits a WARN log when [MediaSource.Local.Bytes] is used for audio
 * or video. Mid-range Android devices typically cap the JVM heap at ~256 MB; loading a 50 MB
 * clip through [MediaSource.Local.Bytes] is already half of that budget once the platform
 * decoder's scratch buffers are accounted for. The 10 MB threshold is conservative: well
 * above any realistic image or short audio clip, but low enough to flag "probably a mistake"
 * video inputs before the caller crashes at runtime. Pinned by CRA-95.
 */
internal const val BYTES_WARN_THRESHOLD: Int = 10 * 1024 * 1024

/**
 * Media category the resolver is materializing for. Drives the [BYTES_WARN_THRESHOLD] warn
 * path — only [AUDIO] / [VIDEO] emit the OOM warning; [IMAGE] does not, because the image
 * compressors short-circuit [MediaSource.Local.Bytes] via direct [android.graphics.BitmapFactory]
 * `decodeByteArray` / `CGImageSourceCreateWithData` decode and never materialize.
 */
internal enum class MediaType { IMAGE, AUDIO, VIDEO }

/**
 * Result of resolving a [MediaSource.Local.Stream] / [MediaSource.Local.Bytes] input to a
 * filesystem path the legacy `compress(inputPath, outputPath, ...)` overloads can consume.
 *
 * @property path Absolute path of the materialized temp file.
 * @property cleanup Idempotent cleanup closure — deletes the temp file and, when the source
 *   was a [MediaSource.Local.Stream] with `closeOnFinish = true`, closes the underlying
 *   [okio.Source]. Safe to invoke on both the success and failure paths (e.g. from a
 *   `try { … } finally { cleanup() }` block).
 */
internal class ResolvedInput(
    val path: String,
    val cleanup: () -> Unit,
)

/**
 * Materialize a [MediaSource.Local.Stream] or [MediaSource.Local.Bytes] input to a temp file
 * under [tempDir] via [materializeToTempFile].
 *
 * For [MediaSource.Local.Bytes] inputs whose size exceeds [BYTES_WARN_THRESHOLD] and whose
 * [mediaType] is not [MediaType.IMAGE], a WARN is emitted through [logger] under
 * [LogTags.IO]. The message includes the exact byte count so consumers can correlate the
 * warning to a specific call site without needing to probe their own byte buffer.
 *
 * **Source ownership:** the returned [ResolvedInput.cleanup] closes the [okio.Source] underlying
 * a [MediaSource.Local.Stream] only when its `closeOnFinish` flag is `true` (the default).
 * [MediaSource.Local.Bytes] has no resource lifecycle — cleanup just deletes the temp file.
 *
 * **Progress:** [onProgress] is forwarded directly to [materializeToTempFile]. Callers are
 * expected to wrap the fraction into a [CompressionProgress] whose [CompressionProgress.Phase]
 * is [CompressionProgress.Phase.MATERIALIZING_INPUT].
 *
 * @throws IllegalStateException if [input] is neither [MediaSource.Local.Stream] nor
 *   [MediaSource.Local.Bytes]. Intended as a programmer-error guard; callers pattern-match
 *   on Stream/Bytes before reaching this helper.
 */
internal suspend fun resolveStreamOrBytesToTempFile(
    input: MediaSource.Local,
    tempDir: Path,
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit = {},
): ResolvedInput = resolveStreamOrBytesToTempFile(
    input = input,
    fileSystem = kompressorFileSystem,
    tempDir = tempDir,
    mediaType = mediaType,
    logger = logger,
    onProgress = onProgress,
)

/**
 * Test-injection overload — identical contract to the primary
 * [resolveStreamOrBytesToTempFile] but routes the temp-file materialization and cleanup
 * through an explicitly-provided [FileSystem] (typically an
 * `okio.fakefilesystem.FakeFileSystem` in commonTest). Kept `internal` so the public surface
 * stays limited to the no-[FileSystem] overload.
 */
// LongParameterList suppressed: seven params is the minimum needed to test-inject the
// FileSystem without mutating the primary overload's signature. Mirrors the
// `materializeToTempFile` test-injection overload and is only reachable from commonTest
// and platform dispatch (where the `fileSystem` arg is a constant).
@Suppress("LongParameterList")
internal suspend fun resolveStreamOrBytesToTempFile(
    input: MediaSource.Local,
    fileSystem: FileSystem,
    tempDir: Path,
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit = {},
): ResolvedInput = when (input) {
    is MediaSource.Local.Stream -> materializeStream(input, fileSystem, tempDir, onProgress)
    is MediaSource.Local.Bytes -> materializeBytes(input, fileSystem, tempDir, mediaType, logger, onProgress)
    else -> error(
        "resolveStreamOrBytesToTempFile only supports MediaSource.Local.Stream / " +
            "MediaSource.Local.Bytes; received ${input::class.simpleName}",
    )
}

private suspend fun materializeStream(
    input: MediaSource.Local.Stream,
    fileSystem: FileSystem,
    tempDir: Path,
    onProgress: suspend (Float) -> Unit,
): ResolvedInput {
    // CURRENTLY a passthrough on this call path: `input` is typed `MediaSource.Local.Stream`
    // and [estimateSourceSize]'s Stream branch echoes `input.sizeHint` back unchanged, so
    // the effective expression reduces to `input.sizeHint ?: input.sizeHint`. The probe's
    // real value lives in its Uri / PFD / NSURL / PHAsset / NSData branches — those get
    // activated in CRA-99 when `materializePfdHandle` (Android) and `materializeNsData`
    // (iOS) migrate to the probe-seeded `TempFileMaterializer` path so pre-materialisation
    // `MATERIALIZING_INPUT` fractions become accurate for native-handle inputs too. Kept in
    // place here so the wiring stays visible + the commonMain Stream callsite picks up the
    // CRA-99 upgrade for free once the probe's Stream branch learns to introspect the
    // source (e.g. via a `MediaSource.Local.Stream.source` cast to `FileSource`).
    // Probe is nullable-returning and never throws, per its contract.
    val effectiveSizeHint: Long? = input.sizeHint ?: estimateSourceSize(input)

    // If `materializeToTempFile` throws (I/O, disk-full, cancellation), we must still honour
    // `closeOnFinish` on the caller-owned source — otherwise the descriptor leaks. The success
    // path defers the close to the returned cleanup closure so the caller controls lifetime;
    // the failure path closes eagerly and rethrows, because no cleanup closure was ever handed
    // out. This preserves the PR's symmetric-on-failure promise for both Stream and Bytes.
    // TooGenericExceptionCaught suppressed: we intentionally close the caller-owned Source for
    // ANY failure mode before rethrowing — `materializeToTempFile` can throw `IOException`,
    // `CancellationException`, or a user-supplied `onProgress` callback can throw arbitrarily.
    // The contract is "`closeOnFinish = true` closes on every exit path", narrower filters would
    // leak the descriptor on whichever mode we didn't enumerate.
    @Suppress("TooGenericExceptionCaught")
    val tempFile = try {
        input.source.materializeToTempFile(fileSystem, tempDir, effectiveSizeHint, onProgress)
    } catch (t: Throwable) {
        if (input.closeOnFinish) runCatching { input.source.close() }
        throw t
    }
    // `closed` flag makes the cleanup closure idempotent: callers sometimes invoke cleanup in a
    // nested `finally` (e.g. in image-compressor double-finally ladders) and some `okio.Source`
    // implementations (`FileInputStream.source()`) throw or have side-effects on double-close.
    var closed = false
    return ResolvedInput(
        path = tempFile.toString(),
        cleanup = {
            runCatching { fileSystem.delete(tempFile, mustExist = false) }
            if (input.closeOnFinish && !closed) {
                closed = true
                runCatching { input.source.close() }
            }
        },
    )
}

// LongParameterList suppressed: the Bytes path needs `fileSystem` for test injection,
// `mediaType` + `logger` for the OOM-WARN gating, and `tempDir` + `onProgress` for the
// shared temp-file materialisation. No sensible parameter object — these are orthogonal
// concerns forwarded verbatim from the caller.
@Suppress("LongParameterList")
private suspend fun materializeBytes(
    input: MediaSource.Local.Bytes,
    fileSystem: FileSystem,
    tempDir: Path,
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit,
): ResolvedInput {
    if (mediaType != MediaType.IMAGE && input.bytes.size > BYTES_WARN_THRESHOLD) {
        logger.warn(LogTags.IO) {
            "MediaSource.Local.Bytes used for ${mediaType.name} with ${input.bytes.size} bytes — " +
                "risk of OOM on devices with limited heap. Prefer FilePath, Uri, NSURL, or Stream."
        }
    }
    // `Buffer` is itself an `okio.Source`; wrapping the byte array once lets us reuse the same
    // chunked-copy path as Stream inputs — O(BUFFER_SIZE) heap, cancellation-safe, progress-
    // reporting — without a dedicated bytes-only code branch in [materializeToTempFile].
    val buffer = Buffer().apply { write(input.bytes) }
    val tempFile = buffer.materializeToTempFile(fileSystem, tempDir, input.bytes.size.toLong(), onProgress)
    return ResolvedInput(
        path = tempFile.toString(),
        cleanup = {
            runCatching { fileSystem.delete(tempFile, mustExist = false) }
        },
    )
}
