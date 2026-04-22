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
import platform.Photos.PHAssetMediaTypeAudio
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceType
import platform.Photos.PHAssetResourceTypeAudio
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo

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
 *  | [IosPHAssetMediaSource]      | Primary `PHAssetResource.valueForKey("fileSize")` (private KVC). |
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
 * (original + edit + adjustment sidecar + live-photo pair + slow-mo fallback). The compressor
 * pipeline only materialises ONE — the primary resource matching [PHAsset.mediaType] — via
 * [platform.Photos.PHImageManager.requestAVAssetForVideo] or
 * [platform.Photos.PHImageManager.requestImageDataAndOrientationForAsset]. Summing every
 * resource would over-report by 2-3× on Live Photos / edited photos and peg consumer progress
 * fractions at <100%, so we pick the single primary resource by type here [PR #144 review,
 * finding #2]. Returns `null` when no matching primary resource is present or when the
 * `valueForKey("fileSize")` probe raises.
 */
private fun phAssetSize(input: IosPHAssetMediaSource): Long? {
    val primaryType: PHAssetResourceType = primaryResourceType(input.asset.mediaType) ?: return null

    @Suppress("UNCHECKED_CAST")
    val resources = runCatching {
        PHAssetResource.assetResourcesForAsset(input.asset) as List<PHAssetResource>
    }.getOrNull().orEmpty()

    val primary = resources.firstOrNull { it.type == primaryType }
    return primary?.let { resource ->
        runCatching {
            (resource.valueForKey("fileSize") as? NSNumber)?.longLongValue
        }.getOrNull()?.takeIf { it >= 0L }
    }
}

/**
 * Primary resource type for a given [PHAsset.mediaType]. Matches what the compressor pipeline
 * will actually materialise via [platform.Photos.PHImageManager.requestAVAssetForVideo] or
 * [platform.Photos.PHImageManager.requestImageDataAndOrientationForAsset] — sidecar / edit /
 * adjustment / live-photo pair / slow-mo fallback resources are ignored.
 */
private fun primaryResourceType(mediaType: Long): PHAssetResourceType? = when (mediaType) {
    PHAssetMediaTypeImage -> PHAssetResourceTypePhoto
    PHAssetMediaTypeVideo -> PHAssetResourceTypeVideo
    PHAssetMediaTypeAudio -> PHAssetResourceTypeAudio
    else -> null
}
