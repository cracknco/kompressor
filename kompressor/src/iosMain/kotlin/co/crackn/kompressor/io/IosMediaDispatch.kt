/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.logging.SafeLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.Path.Companion.toPath
import platform.Foundation.NSURL

/**
 * iOS-side dispatch from the public [MediaSource] / [MediaDestination] contract into a
 * filesystem path the iOS compressors' private `compressFilePath(inputPath, outputPath, ...)`
 * helpers can consume.
 *
 * Sibling of `AndroidMediaDispatch` (androidMain). [IosUrlMediaSource] /
 * [IosPHAssetMediaSource] / [IosDataMediaSource] / [IosUrlMediaDestination] live in
 * `iosMain` as platform extensions and cannot be referenced from `commonMain` — so the
 * dispatch for iOS-specific subtypes lives here. The iOS compressors call [toIosInputPath]
 * / [toIosOutputHandle].
 *
 * [toIosInputPath] is `suspend` — resolving a [platform.Photos.PHAsset] requires a
 * coroutine-cancellable callback into PhotoKit, and [MediaSource.Local.Stream] /
 * [MediaSource.Local.Bytes] / [IosDataMediaSource] inputs are materialized to a temp file via
 * [resolveStreamOrBytesToTempFile] (CRA-95). The [mediaType] parameter drives the OOM-warning
 * path in that resolver ([MediaType.IMAGE] skips the warn because the image compressor short-
 * circuits Stream/Bytes/NSData before calling dispatch).
 *
 * [toIosOutputHandle] remains synchronous. Stream outputs allocate a temp path and defer the
 * actual temp→sink copy to the commit step, which is suspending so progress ticks reach the
 * caller during [CompressionProgress.Phase.FINALIZING_OUTPUT].
 */

/** Result of resolving a [MediaSource] to an iOS filesystem path. */
internal class IosInputHandle(
    /** Absolute filesystem path the legacy compress(String, String, ...) overload accepts. */
    val path: String,
    private val cleanupFn: () -> Unit,
) {
    /** Release temp resources (materialized temp file, closed Source). Idempotent. */
    fun cleanup() {
        cleanupFn()
    }
}

/**
 * Result of resolving a [MediaDestination] to an iOS filesystem path.
 *
 * For `FilePath` / `IosUrlMediaDestination` destinations the compressor writes directly to
 * [tempPath] and both [commit] and [cleanup] are no-ops. For [MediaDestination.Local.Stream]
 * [commit] streams the temp file into the consumer sink, emitting progress ticks as it goes.
 */
internal class IosOutputHandle(
    /** Path the legacy compress(String, String, ...) overload writes to. */
    val tempPath: String,
    private val commitFn: suspend (suspend (Float) -> Unit) -> Unit,
    private val cleanupFn: () -> Unit,
) {
    /**
     * Publish the compressed bytes from [tempPath] to the caller's destination. No-op for
     * FilePath / IosUrlMediaDestination. For Stream destinations the copy is chunked and
     * [onProgress] is invoked with a fraction in `[0.0, 1.0]` per 64 KB chunk.
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
 * Resolve a [MediaSource] to an iOS input path.
 *
 *  - [MediaSource.Local.FilePath] → direct path, no temp, no cleanup.
 *  - [IosUrlMediaSource] → unwrap `file://` URL to its filesystem path via [NSURL.path]. No
 *    materialization: the iOS compressors already accept file paths and the URL was
 *    scheme-validated at build time in [MediaSource.Companion.of] `(url: NSURL)`.
 *  - [IosPHAssetMediaSource] → call [PHAsset.resolveToUrl] (suspend + cancellation-safe). For
 *    video/audio the URL points at PhotoKit's local cache and needs no cleanup; for images the
 *    resolver writes to a temp file under [kompressorTempDir] and the cleanup hook deletes it.
 *  - [IosDataMediaSource] / [MediaSource.Local.Stream] / [MediaSource.Local.Bytes] →
 *    materialized via [resolveStreamOrBytesToTempFile] (NSData is unwrapped to a byte array
 *    and routed through the Bytes path). The image compressor short-circuits all three before
 *    calling this dispatch — the branches here are reachable only on audio / video.
 *  - Everything else (cross-platform wrapper slipped in) → fail loudly.
 */
internal suspend fun MediaSource.toIosInputPath(
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit = {},
): IosInputHandle = when (this) {
    is MediaSource.Local.FilePath -> IosInputHandle(path, cleanupFn = {})
    is IosUrlMediaSource -> IosInputHandle(
        path = url.path ?: throw UnsupportedOperationException(
            "IosUrlMediaSource has null NSURL.path (cannot dispatch to filesystem compressor)",
        ),
        cleanupFn = {},
    )
    is IosPHAssetMediaSource -> resolvePhAssetHandle(this)
    is IosDataMediaSource -> materializeNsData(this, mediaType, logger, onProgress)
    // Split into two `is` arms so the smart-cast narrows to the specific subtype — the
    // `MediaSource.Local` parameter on [materializeStreamOrBytes] accepts either directly.
    is MediaSource.Local.Stream -> materializeStreamOrBytes(this, mediaType, logger, onProgress)
    is MediaSource.Local.Bytes -> materializeStreamOrBytes(this, mediaType, logger, onProgress)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaSource subtype on iOS: ${this::class.simpleName}",
    )
}

/**
 * Resolve a [MediaDestination] to an iOS output path.
 *
 *  - [MediaDestination.Local.FilePath] → direct path, no temp, no commit.
 *  - [IosUrlMediaDestination] → unwrap `file://` URL to its filesystem path.
 *  - [MediaDestination.Local.Stream] → write to private temp, commit streams temp → sink in
 *    64 KB chunks and emits `FINALIZING_OUTPUT` progress through the caller's [onProgress].
 */
internal fun MediaDestination.toIosOutputHandle(): IosOutputHandle = when (this) {
    is MediaDestination.Local.FilePath -> IosOutputHandle(
        tempPath = path,
        commitFn = { _ -> },
        cleanupFn = {},
    )
    is IosUrlMediaDestination -> IosOutputHandle(
        tempPath = url.path ?: throw UnsupportedOperationException(
            "IosUrlMediaDestination has null NSURL.path (cannot dispatch to filesystem compressor)",
        ),
        commitFn = { _ -> },
        cleanupFn = {},
    )
    is MediaDestination.Local.Stream -> streamOutputHandle(this)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaDestination subtype on iOS: ${this::class.simpleName}",
    )
}

private suspend fun materializeStreamOrBytes(
    input: MediaSource.Local,
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit,
): IosInputHandle {
    val resolved = resolveStreamOrBytesToTempFile(
        input = input,
        tempDir = kompressorTempDir(),
        mediaType = mediaType,
        logger = logger,
        onProgress = onProgress,
    )
    return renameToAvRecognizableExtension(resolved, mediaType)
}

/**
 * Materialize an [IosDataMediaSource] to a temp file by routing its [NSData] payload through the
 * common [resolveStreamOrBytesToTempFile] Bytes path. Copies the NSData byte range into a
 * Kotlin [ByteArray] once; the resolver then reuses the same chunked-copy pipeline as
 * [MediaSource.Local.Bytes] inputs (O(BUFFER_SIZE) heap, cancellation-safe, progress-reporting).
 *
 * The NSData → ByteArray copy is inherent to the Kotlin/Native bridge — there is no zero-copy
 * path because okio's [Buffer.write] does not accept a raw pointer. For the realistic audio /
 * video NSData sizes this helper targets (≤ a few hundred MB) the copy is dwarfed by the
 * downstream decode / encode cost.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun materializeNsData(
    input: IosDataMediaSource,
    mediaType: MediaType,
    logger: SafeLogger,
    onProgress: suspend (Float) -> Unit,
): IosInputHandle {
    val length = input.data.length.toInt()
    val bytes = ByteArray(length)
    if (length > 0) {
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), input.data.bytes, length.toULong())
        }
    }
    val resolved = resolveStreamOrBytesToTempFile(
        input = MediaSource.Local.Bytes(bytes),
        tempDir = kompressorTempDir(),
        mediaType = mediaType,
        logger = logger,
        onProgress = onProgress,
    )
    return renameToAvRecognizableExtension(resolved, mediaType)
}

/**
 * Rename the materialized `.bin` temp file to an extension AVFoundation's UTI-based format
 * detection accepts.
 *
 * **Why rename at all?** AVFoundation (`AVURLAsset`) derives the Uniform Type Identifier
 * (UTI) from the URL's `pathExtension`. A `.bin` file maps to the generic `public.data`
 * UTI and `AVURLAsset.tracks` returns empty — the audio / video compressors then raise
 * `UnsupportedSourceFormat` even though the bytes on disk are perfectly valid WAV / MP4.
 * Android's `MediaExtractor` content-sniffs independently of extension, so this is an
 * iOS-only workaround.
 *
 * The function peeks the first 12 bytes to detect the container format:
 *   - `RIFF....WAVE` → `.wav`
 *   - `....ftyp...` → `.mp4` for [MediaType.VIDEO], `.m4a` for [MediaType.AUDIO]
 *   - `ID3` prefix → `.mp3`
 *   - unknown → media-type default (`.mp4` for video, `.m4a` for audio)
 *
 * The temp file is moved (same filesystem, atomic on APFS) so the cleanup lambda only
 * needs to track the new path. Errors during the peek or rename fall through to the
 * default extension — worst case AVFoundation rejects the rename and the caller sees
 * the same `UnsupportedSourceFormat` it would have seen without the workaround.
 */
private fun renameToAvRecognizableExtension(
    resolved: ResolvedInput,
    mediaType: MediaType,
): IosInputHandle {
    val extension = detectInputExtension(resolved.path, mediaType)
    val originalPath = resolved.path.toPath()
    val renamedPath = originalPath.parent!! / "${originalPath.name.substringBeforeLast('.')}.$extension"
    // Defensive guard: `MediaType.IMAGE` falls through to `DEFAULT_IMAGE_EXTENSION = "bin"`,
    // which equals the materialiser's own suffix and would collapse `originalPath` ==
    // `renamedPath`. AVFoundation never sees image inputs on this path (image compressors
    // short-circuit Bytes/Stream before dispatch), but a future refactor could expose it —
    // `atomicMove` semantics for self-moves are filesystem-dependent, so avoid the call.
    if (originalPath == renamedPath) {
        return IosInputHandle(path = resolved.path, cleanupFn = resolved.cleanup)
    }
    return runCatching {
        kompressorFileSystem.atomicMove(originalPath, renamedPath)
        IosInputHandle(
            path = renamedPath.toString(),
            cleanupFn = {
                // Invoke the resolver's cleanup first (closes Source if Stream, handles
                // accounting). Then remove the renamed path — the resolver's default
                // cleanup targets the original .bin which no longer exists.
                resolved.cleanup()
                runCatching { kompressorFileSystem.delete(renamedPath) }
            },
        )
    }.getOrElse {
        // If rename fails (cross-filesystem move or permission), fall back to the .bin
        // path. AVFoundation will almost certainly reject it, but the caller will see a
        // typed error rather than an opaque crash.
        IosInputHandle(path = resolved.path, cleanupFn = resolved.cleanup)
    }
}

/**
 * Peek magic bytes to choose an AVFoundation-friendly extension. Media-type fallbacks cover
 * encrypted or truncated inputs that still carry a real media payload AVFoundation can
 * decode after content-sniffing past the extension hint.
 */
private fun detectInputExtension(path: String, mediaType: MediaType): String {
    val magic = peekMagicBytes(path)
    val defaultExtension = when (mediaType) {
        MediaType.VIDEO -> DEFAULT_VIDEO_EXTENSION
        MediaType.AUDIO -> DEFAULT_AUDIO_EXTENSION
        MediaType.IMAGE -> DEFAULT_IMAGE_EXTENSION
    }
    if (magic.size < MAGIC_MIN_BYTES) return defaultExtension

    val fourccHead = magic.decodeToString(0, 4)
    val fourccAt8 = if (magic.size >= 12) magic.decodeToString(8, 12) else ""
    val fourccAt4 = if (magic.size >= 8) magic.decodeToString(4, 8) else ""

    return when {
        fourccHead == "RIFF" && fourccAt8 == "WAVE" -> "wav"
        fourccAt4 == "ftyp" -> if (mediaType == MediaType.VIDEO) "mp4" else "m4a"
        fourccHead.startsWith("ID3") || isMp3FrameSync(magic) -> "mp3"
        else -> defaultExtension
    }
}

/** Read up to [MAGIC_BYTE_PEEK] bytes from [path]. Never throws — returns empty on I/O error. */
private fun peekMagicBytes(path: String): ByteArray {
    val buffer = Buffer()
    runCatching {
        val src = kompressorFileSystem.source(path.toPath())
        try {
            src.read(buffer, MAGIC_BYTE_PEEK.toLong())
        } finally {
            src.close()
        }
    }
    return buffer.readByteArray()
}

/**
 * Detect a raw MPEG audio-frame sync word — 11 bits set in the first two bytes plus a Layer-III
 * marker in the layer field. Covers the typical CBR / VBR MP3 streams that skip the ID3v2
 * header, which `.wav` / `ftyp` detection would otherwise miss — without this check, those
 * files fall through to the `.m4a` default and AVFoundation rejects them on UTI mismatch
 * [PR #143 review, finding #6].
 */
private fun isMp3FrameSync(magic: ByteArray): Boolean {
    if (magic.size < 2) return false
    val b0 = magic[0].toInt() and 0xFF
    val b1 = magic[1].toInt() and 0xFF
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) == 0x02
}

private const val MAGIC_BYTE_PEEK = 16
private const val MAGIC_MIN_BYTES = 8
private const val DEFAULT_VIDEO_EXTENSION = "mp4"
private const val DEFAULT_AUDIO_EXTENSION = "m4a"
private const val DEFAULT_IMAGE_EXTENSION = "bin"

private fun streamOutputHandle(dest: MediaDestination.Local.Stream): IosOutputHandle {
    val outSink = createStreamOutputSink(dest, kompressorTempDir())
    return IosOutputHandle(
        tempPath = outSink.tempPath,
        commitFn = { onProgress -> outSink.publish(onProgress) },
        cleanupFn = { outSink.cleanup() },
    )
}

private suspend fun resolvePhAssetHandle(source: IosPHAssetMediaSource): IosInputHandle {
    // `ResolvedPhAsset.ownsFile` is the explicit contract carried back from the resolver: true
    // when Kompressor materialised a temp file (PHAsset image path), false when PhotoKit owns
    // the URL (video/audio cached asset). Replaces the earlier path-substring-sniff
    // (`resolvedPath.contains("/kmp_phasset_image_")`) that coupled this cleanup logic to the
    // resolver's filename scheme at a distance [PR #142 review, finding #5].
    val resolved = source.asset.resolveToUrl(source.allowNetworkAccess)
    val resolvedPath = resolved.url.path
        ?: throw UnsupportedOperationException(
            "PHAssetResolver returned NSURL with null path (localIdentifier=${source.asset.localIdentifier})",
        )
    val cleanup: () -> Unit = if (resolved.ownsFile) {
        { runCatching { kompressorFileSystem.delete(resolvedPath.toPath()) } }
    } else {
        {}
    }
    return IosInputHandle(resolvedPath, cleanup)
}
