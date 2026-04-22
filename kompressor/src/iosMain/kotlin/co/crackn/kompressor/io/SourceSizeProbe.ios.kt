/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.valueForKey
import platform.Photos.PHAssetResource

/**
 * iOS actual of [estimateSourceSize].
 *
 * Dispatch matrix:
 *
 *  | Variant                   | Source of truth                                              |
 *  | ------------------------- | ------------------------------------------------------------ |
 *  | [MediaSource.Local.FilePath] | `NSFileManager.attributesOfItemAtPath`[`NSFileSize`].        |
 *  | [MediaSource.Local.Stream]   | Caller-supplied [MediaSource.Local.Stream.sizeHint].         |
 *  | [MediaSource.Local.Bytes]    | `bytes.size.toLong()` — always authoritative.                |
 *  | [IosUrlMediaSource]          | `url.path` → same `NSFileSize` lookup as `FilePath`.         |
 *  | [IosPHAssetMediaSource]      | `PHAssetResource.valueForKey("fileSize")` (private KVC).     |
 *  | [IosDataMediaSource]         | `NSData.length.toLong()` — O(1), always authoritative.       |
 *
 * **PHAssetResource.fileSize disclaimer.** The `fileSize` property is not part of PhotoKit's
 * public Swift/ObjC headers, but has been a stable private KVC key on `PHAssetResource` since
 * iOS 9 — used by every third-party library that needs accurate PHAsset sizing. Apple's
 * private-API review does NOT reject `valueForKey("fileSize")` on `PHAssetResource` as of iOS 17.
 * If a future OS removes the key, the mandatory [runCatching] wrapper downgrades the probe to
 * `null` — the consumer pipeline still compresses, it just falls back to "no progress fraction"
 * during materialization.
 */
internal actual fun estimateSourceSize(input: MediaSource.Local): Long? = when (input) {
    is MediaSource.Local.FilePath -> sizeOfPath(input.path)
    is MediaSource.Local.Stream -> input.sizeHint
    is MediaSource.Local.Bytes -> input.bytes.size.toLong()
    is IosUrlMediaSource -> input.url.path?.let(::sizeOfPath)
    is IosPHAssetMediaSource -> phAssetSize(input)
    is IosDataMediaSource -> input.data.length.toLong()
    else -> null
}

/**
 * Bare `NSFileManager.attributesOfItemAtPath` read — the sibling `nsFileSize` helper under
 * `co.crackn.kompressor` throws when the file is missing (it's tuned for compressor input
 * validation). The probe contract requires a silent `null` on any failure, so this helper does
 * its own attribute fetch and funnels every failure through [runCatching].
 */
private fun sizeOfPath(path: String): Long? = runCatching {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
    (attrs[NSFileSize] as? NSNumber)?.longLongValue
}.getOrNull()

/**
 * PhotoKit sizing. Each [PHAsset][platform.Photos.PHAsset] may own multiple [PHAssetResource]s
 * (original photo + edit + adjustment sidecar + live-photo pair video). We sum every resource's
 * `fileSize` to report the total on-disk footprint — matches what `PHImageManager` will
 * materialise when the compressor pipeline actually requests the asset. Returns `null` when the
 * asset owns no resources or when every `valueForKey("fileSize")` raises.
 */
private fun phAssetSize(input: IosPHAssetMediaSource): Long? {
    @Suppress("UNCHECKED_CAST")
    val resources = runCatching {
        PHAssetResource.assetResourcesForAsset(input.asset) as List<PHAssetResource>
    }.getOrNull().orEmpty()
    var total = 0L
    var any = false
    resources.forEach { resource ->
        val size = runCatching {
            (resource.valueForKey("fileSize") as? NSNumber)?.longLongValue
        }.getOrNull()
        if (size != null && size >= 0L) {
            total += size
            any = true
        }
    }
    return if (any) total else null
}
