/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVURLAsset
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeAudio
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsVersionCurrent
import platform.Photos.PHVideoRequestOptions
import platform.Photos.PHVideoRequestOptionsVersionCurrent

/**
 * Resolve a [PHAsset] into a local `file://` [NSURL] the legacy
 * `compress(inputPath, outputPath, …)` overloads can consume.
 *
 * Dispatches on [PHAsset.mediaType]:
 *
 *  - **Video / Audio** — `PHImageManager.requestAVAssetForVideo(...)` returns an [AVAsset]; when
 *    the asset is locally stored, AVFoundation delivers an [AVURLAsset] whose `.URL` is a
 *    `file://` URL pointing at the cached asset. That URL is returned directly — no extra copy.
 *    If the asset is iCloud-only and [allowNetworkAccess] is `true`, PhotoKit blocks the
 *    completion handler on the iCloud download before delivering the result (seconds to minutes
 *    depending on bandwidth); when `false`, PhotoKit signals `PHImageResultIsInCloudKey=true` in
 *    the info dictionary and we raise [PHAssetIcloudOnlyException] so the compressor can map it
 *    to the typed `SourceNotFound` error per media type.
 *
 *  - **Image** — `PHImageManager.requestImageDataAndOrientationForAsset(...)` returns an
 *    [NSData] buffer; we materialise the bytes to a private temp file under
 *    `kompressorTempDir()` and return its file URL. This temp file is not auto-deleted by the
 *    resolver — the caller (dispatch layer) is responsible for cleanup via the
 *    [IosInputHandle.cleanup] hook so failures mid-compression don't leak the temp file.
 *
 * The underlying PhotoKit call is cancellation-safe: on coroutine cancellation the resolver
 * cancels the in-flight PhotoKit request via `PHImageManager.cancelImageRequest(requestId)` so
 * the system can release its download slot immediately instead of waiting for the iCloud
 * transfer to complete.
 *
 * @param allowNetworkAccess When `false`, iCloud-only assets fail fast with
 *   [PHAssetIcloudOnlyException] instead of blocking on the download. See
 *   [MediaSource.Companion.of] `(asset: PHAsset, allowNetworkAccess: Boolean)` for the full
 *   rationale.
 *
 * @throws PHAssetIcloudOnlyException when [allowNetworkAccess] is `false` and the asset is
 *   iCloud-only.
 * @throws PHAssetResolutionException when PhotoKit returns an error, the request is cancelled by
 *   PhotoKit, or the unsupported `PHAssetMediaTypeUnknown` is encountered.
 * @throws kotlinx.coroutines.CancellationException when the caller cancels the coroutine.
 */
internal suspend fun PHAsset.resolveToUrl(allowNetworkAccess: Boolean): NSURL = when (mediaType) {
    PHAssetMediaTypeVideo, PHAssetMediaTypeAudio -> resolveVideoOrAudioToUrl(allowNetworkAccess)
    PHAssetMediaTypeImage -> resolveImageToUrl(allowNetworkAccess)
    else -> throw PHAssetResolutionException(
        "Unsupported PHAsset mediaType: $mediaType (localIdentifier=$localIdentifier)",
    )
}

/**
 * Video / Audio path: `requestAVAssetForVideo` → `(AVURLAsset).URL`.
 *
 * PhotoKit delivers the completion handler on an internal queue; wrapping the call in
 * [suspendCancellableCoroutine] with [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation]
 * lets us cancel the request cleanly if the caller's coroutine is cancelled mid-flight.
 */
private suspend fun PHAsset.resolveVideoOrAudioToUrl(allowNetworkAccess: Boolean): NSURL {
    val options = PHVideoRequestOptions().apply {
        networkAccessAllowed = allowNetworkAccess
        version = PHVideoRequestOptionsVersionCurrent
    }
    val asset = this

    return suspendCancellableCoroutine { cont ->
        val requestId = PHImageManager.defaultManager().requestAVAssetForVideo(
            asset = asset,
            options = options,
        ) { avAsset, _, info ->
            if (cont.isCompleted) return@requestAVAssetForVideo
            when (val outcome = classifyVideoOutcome(avAsset, info, allowNetworkAccess)) {
                is VideoOutcome.Success -> cont.resume(outcome.url)
                is VideoOutcome.Failure -> cont.resumeWithException(outcome.cause)
            }
        }
        cont.invokeOnCancellation {
            PHImageManager.defaultManager().cancelImageRequest(requestId)
        }
    }
}

/**
 * Image path: `requestImageDataAndOrientationForAsset` → NSData → private temp file. The temp
 * file lives under [kompressorTempDir] with a `kmp_phasset_image_*.bin` prefix so the dispatch
 * cleanup hook can sweep it on failure.
 */
private suspend fun PHAsset.resolveImageToUrl(allowNetworkAccess: Boolean): NSURL {
    val options = PHImageRequestOptions().apply {
        networkAccessAllowed = allowNetworkAccess
        version = PHImageRequestOptionsVersionCurrent
        // Synchronous=false keeps the call off the current queue; the coroutine continuation
        // is resumed from PhotoKit's internal queue, same pattern as the video/audio path.
        synchronous = false
    }
    val asset = this

    val data: NSData = suspendCancellableCoroutine { cont ->
        val requestId = PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(
            asset = asset,
            options = options,
        ) { imageData, _, _, info ->
            if (cont.isCompleted) return@requestImageDataAndOrientationForAsset
            when (val outcome = classifyImageOutcome(imageData, info, allowNetworkAccess)) {
                is ImageOutcome.Success -> cont.resume(outcome.data)
                is ImageOutcome.Failure -> cont.resumeWithException(outcome.cause)
            }
        }
        cont.invokeOnCancellation {
            PHImageManager.defaultManager().cancelImageRequest(requestId)
        }
    }

    return writeImageDataToTempFile(data, asset.localIdentifier)
}

private sealed interface VideoOutcome {
    class Success(val url: NSURL) : VideoOutcome
    class Failure(val cause: Throwable) : VideoOutcome
}

private sealed interface ImageOutcome {
    class Success(val data: NSData) : ImageOutcome
    class Failure(val cause: Throwable) : ImageOutcome
}

/**
 * Classify the PhotoKit video/audio completion handler's arguments into a success/failure pair.
 * Kept as a pure helper so the `resolveVideoOrAudioToUrl` coroutine body stays under detekt's
 * `ComplexMethod` / `LongMethod` thresholds.
 *
 * The info dictionary priority mirrors Apple's own documentation: an `PHImageCancelledKey=true`
 * entry always wins (K/N surfaces the flag via the info map; we translate to
 * `CancellationException` so `suspendRunCatching` doesn't wrap it into `Result.failure`), then a
 * `PHImageErrorKey` NSError, then the `PHImageResultIsInCloudKey=true` iCloud-only signal, then
 * the happy path.
 */
@Suppress("ReturnCount")
private fun classifyVideoOutcome(
    avAsset: AVAsset?,
    info: Map<Any?, *>?,
    allowNetworkAccess: Boolean,
): VideoOutcome {
    if (isCancelledInfo(info)) {
        return VideoOutcome.Failure(CancellationException("PhotoKit request cancelled"))
    }
    errorFromInfo(info)?.let {
        return VideoOutcome.Failure(PHAssetResolutionException("PhotoKit error: ${it.localizedDescription}"))
    }
    if (!allowNetworkAccess && isIcloudOnlyInfo(info)) {
        return VideoOutcome.Failure(
            PHAssetIcloudOnlyException("Asset is iCloud-only and allowNetworkAccess=false"),
        )
    }
    val urlAsset = avAsset as? AVURLAsset
        ?: return VideoOutcome.Failure(
            PHAssetResolutionException(
                "PhotoKit returned non-AVURLAsset (cannot extract file URL): " +
                    (avAsset?.let { it::class.simpleName } ?: "null"),
            ),
        )
    return VideoOutcome.Success(urlAsset.URL)
}

/** Sibling of [classifyVideoOutcome] for the image `requestImageDataAndOrientationForAsset` path. */
@Suppress("ReturnCount")
private fun classifyImageOutcome(
    imageData: NSData?,
    info: Map<Any?, *>?,
    allowNetworkAccess: Boolean,
): ImageOutcome {
    if (isCancelledInfo(info)) {
        return ImageOutcome.Failure(CancellationException("PhotoKit request cancelled"))
    }
    errorFromInfo(info)?.let {
        return ImageOutcome.Failure(PHAssetResolutionException("PhotoKit error: ${it.localizedDescription}"))
    }
    if (!allowNetworkAccess && isIcloudOnlyInfo(info)) {
        return ImageOutcome.Failure(
            PHAssetIcloudOnlyException("Asset is iCloud-only and allowNetworkAccess=false"),
        )
    }
    if (imageData == null) {
        return ImageOutcome.Failure(PHAssetResolutionException("PhotoKit returned null imageData"))
    }
    return ImageOutcome.Success(imageData)
}

private fun isCancelledInfo(info: Map<Any?, *>?): Boolean = info?.get("PHImageCancelledKey") == true

private fun isIcloudOnlyInfo(info: Map<Any?, *>?): Boolean = info?.get("PHImageResultIsInCloudKey") == true

private fun errorFromInfo(info: Map<Any?, *>?): NSError? = info?.get("PHImageErrorKey") as? NSError

/**
 * Write [data] to a private temp file under [kompressorTempDir]. The caller owns cleanup — the
 * resolver does not delete this file so the compressor can use it for the full `compress()` run
 * and remove it from the dispatch's `cleanup` hook on termination (success or failure).
 */
@Suppress("MagicNumber")
private fun writeImageDataToTempFile(data: NSData, identifier: String): NSURL {
    val tempDir = kompressorTempDir()
    // Ensure the temp directory exists — matches the Android sibling's `.mkdirs()` pattern.
    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
        tempDir.toString(), withIntermediateDirectories = true, attributes = null, error = null,
    )
    // Sanitise the PHAsset localIdentifier for use in a filename: strip slashes that could
    // escape the temp dir, and cap length so pathological identifiers don't blow past the
    // filesystem's NAME_MAX.
    val safeId = identifier.replace('/', '_').take(32)
    val filename = "kmp_phasset_image_${safeId}_${NSUUID().UUIDString}.bin"
    val fileUrl = NSURL.fileURLWithPath("$tempDir/$filename")
    val written = data.writeToURL(fileUrl, atomically = true)
    if (!written) {
        throw PHAssetResolutionException("Failed to materialise PHAsset image data to temp file: $fileUrl")
    }
    return fileUrl
}

/** Raised when the PhotoKit request surfaces a non-iCloud error or an unrecoverable state. */
internal class PHAssetResolutionException(message: String) : RuntimeException(message)

/**
 * Raised when [PHAsset.resolveToUrl] is called with `allowNetworkAccess=false` and the asset
 * is stored only in iCloud. Compressors catch this and translate it into the media-specific
 * typed `SourceNotFound` error so callers across image / audio / video see a consistent subtype.
 */
internal class PHAssetIcloudOnlyException(message: String) : RuntimeException(message)
