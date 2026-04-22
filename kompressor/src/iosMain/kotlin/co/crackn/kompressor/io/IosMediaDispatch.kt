/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import okio.Path.Companion.toPath
import platform.Foundation.NSURL

/**
 * iOS-side dispatch from the public [MediaSource] / [MediaDestination] contract into a
 * filesystem path the legacy `compress(inputPath, outputPath, ...)` overloads can consume.
 *
 * Sibling of `AndroidMediaDispatch` (androidMain). The common
 * [co.crackn.kompressor.io.requireFilePathOrThrow] cannot reference [IosUrlMediaSource] /
 * [IosPHAssetMediaSource] / [IosDataMediaSource] / [IosUrlMediaDestination] — those live in
 * `iosMain` as platform extensions. The iOS compressors call [toIosInputPath] /
 * [toIosOutputHandle] here; `commonMain` variants they cannot resolve fall through to the
 * `else` branch and raise the canonical "CRA-95" message for future Stream / Bytes support.
 *
 * [toIosInputPath] is `suspend` — unlike the Android sibling, resolving a [PHAsset] requires a
 * coroutine-cancellable callback into PhotoKit. Callers dispatch from inside the existing
 * `suspendRunCatching` wrapper so no extra structured-concurrency plumbing is required.
 */

/** Result of resolving a [MediaSource] to an iOS filesystem path. */
internal class IosInputHandle(
    /** Absolute filesystem path the legacy compress(String, String, ...) overload accepts. */
    val path: String,
    private val cleanupFn: () -> Unit,
) {
    /** Release temp resources (materialized temp file from PHAsset image path). Idempotent. */
    fun cleanup() {
        cleanupFn()
    }
}

/**
 * Result of resolving a [MediaDestination] to an iOS filesystem path. iOS has no MediaStore-style
 * two-phase commit — `file://` NSURL outputs write directly to the unwrapped path — so [commit]
 * is a no-op for every current wrapper. Kept in the API shape for parity with
 * [co.crackn.kompressor.io.AndroidOutputHandle] and to leave room for future destinations that
 * do need a commit step (e.g. `PHAsset` asset-creation request from CRA-95).
 */
internal class IosOutputHandle(
    /** Path the legacy compress(String, String, ...) overload writes to. */
    val tempPath: String,
    private val commitFn: () -> Unit,
    private val cleanupFn: () -> Unit,
) {
    /** Publish the compressed bytes from [tempPath] to the caller's destination. No-op today. */
    fun commit() {
        commitFn()
    }

    /** Release temp resources (temp file). Idempotent. */
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
 *  - [IosDataMediaSource] / [MediaSource.Local.Stream] / [MediaSource.Local.Bytes] → throw
 *    `UnsupportedOperationException` pointing at CRA-95. The image compressor short-circuits
 *    [IosDataMediaSource] *before* calling this dispatch (via [CGImageSourceCreateWithData]
 *    zero-copy decode) so this branch only fires on audio / video.
 */
internal suspend fun MediaSource.toIosInputPath(): IosInputHandle = when (this) {
    is MediaSource.Local.FilePath -> IosInputHandle(path, cleanupFn = {})
    is IosUrlMediaSource -> IosInputHandle(
        path = url.path ?: throw UnsupportedOperationException(
            "IosUrlMediaSource has null NSURL.path (cannot dispatch to filesystem compressor)",
        ),
        cleanupFn = {},
    )
    is IosPHAssetMediaSource -> resolvePhAssetHandle(this)
    is IosDataMediaSource -> throw UnsupportedOperationException(DATA_INPUT_MSG)
    is MediaSource.Local.Stream -> throw UnsupportedOperationException(STREAM_INPUT_MSG)
    is MediaSource.Local.Bytes -> throw UnsupportedOperationException(BYTES_INPUT_MSG)
    // Platform wrappers from the sibling platform (e.g. Android `AndroidUriMediaSource`) would
    // land here if a caller cross-wired platforms. Fail loudly with the wrapper class name.
    else -> throw UnsupportedOperationException(
        "Unsupported MediaSource subtype on iOS: ${this::class.simpleName}",
    )
}

/**
 * Resolve a [MediaDestination] to an iOS output path.
 *
 *  - [MediaDestination.Local.FilePath] → direct path, no temp, no commit.
 *  - [IosUrlMediaDestination] → unwrap `file://` URL to its filesystem path.
 *  - [MediaDestination.Local.Stream] → throw `UnsupportedOperationException` pointing at CRA-95.
 */
internal fun MediaDestination.toIosOutputHandle(): IosOutputHandle = when (this) {
    is MediaDestination.Local.FilePath -> IosOutputHandle(
        tempPath = path,
        commitFn = {},
        cleanupFn = {},
    )
    is IosUrlMediaDestination -> IosOutputHandle(
        tempPath = url.path ?: throw UnsupportedOperationException(
            "IosUrlMediaDestination has null NSURL.path (cannot dispatch to filesystem compressor)",
        ),
        commitFn = {},
        cleanupFn = {},
    )
    is MediaDestination.Local.Stream -> throw UnsupportedOperationException(STREAM_OUTPUT_MSG)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaDestination subtype on iOS: ${this::class.simpleName}",
    )
}

private suspend fun resolvePhAssetHandle(source: IosPHAssetMediaSource): IosInputHandle {
    val url: NSURL = source.asset.resolveToUrl(source.allowNetworkAccess)
    val resolvedPath = url.path
        ?: throw UnsupportedOperationException(
            "PHAssetResolver returned NSURL with null path (localIdentifier=${source.asset.localIdentifier})",
        )
    // Image-path resolution materializes to a private temp file under kompressorTempDir;
    // video/audio returns PhotoKit's own cached URL which we must NOT delete. Detect the
    // resolver-owned temp files by prefix — safe because we control the naming scheme in
    // [PHAssetResolver.writeImageDataToTempFile].
    val ownsFile = resolvedPath.contains("/kmp_phasset_image_")
    val cleanup: () -> Unit = if (ownsFile) {
        { runCatching { kompressorFileSystem.delete(resolvedPath.toPath()) } }
    } else {
        {}
    }
    return IosInputHandle(resolvedPath, cleanup)
}

// --- Error messages mirror commonMain/FilePathDispatch.kt so the legacy and iOS-aware paths ---
// throw byte-identical strings. Keep these in sync if commonMain's messages change.

private const val STREAM_INPUT_MSG: String =
    "MediaSource.Local.Stream input will be supported in CRA-95. " +
        "For now, use MediaSource.Local.FilePath."
private const val BYTES_INPUT_MSG: String =
    "MediaSource.Local.Bytes input will be supported in CRA-95. " +
        "For now, use MediaSource.Local.FilePath."
private const val STREAM_OUTPUT_MSG: String =
    "MediaDestination.Local.Stream output will be supported in CRA-95. " +
        "For now, use MediaDestination.Local.FilePath."
private const val DATA_INPUT_MSG: String =
    "MediaSource.of(NSData) input will be supported for audio/video in CRA-95. " +
        "For now, use NSURL, PHAsset, or MediaSource.Local.FilePath."
