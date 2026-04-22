/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import platform.Foundation.NSData
import platform.Foundation.NSInputStream
import platform.Foundation.NSURL
import platform.Photos.PHAsset

/**
 * Create a [MediaSource] from a file-system [NSURL].
 *
 * Accepts `file://` URLs — typical output of `UIImagePickerController` / `PHPickerViewController`
 * after exporting a picked asset. Rejects remote schemes `http` / `https` by design:
 * ```
 * IllegalArgumentException: Remote URLs not supported. Download the content locally first.
 * ```
 * The rejection message is a cross-platform invariant shared with the Android builder sibling
 * (CRA-93) so a consumer switching platforms sees byte-identical text. The constant lives in
 * [MediaSourceRejections] (commonMain) — a typo fix on one side lands for every platform
 * simultaneously.
 *
 * The returned [MediaSource.Local] is consumed by the
 * `compress(MediaSource, MediaDestination, ...)` overload introduced in CRA-92. `file://` URLs
 * are unwrapped to their filesystem path and pass through the existing iOS compressor pipeline
 * (`AVURLAsset(url:)` for audio/video, `UIImage(contentsOfFile:)` / `CGImageSourceCreateWithURL`
 * for images); no materialization to disk is performed.
 *
 * @param url The `file://` URL to compress from.
 * @return A [MediaSource.Local] that the iOS compressors recognise as an NSURL source.
 * @throws IllegalArgumentException if [url]'s scheme is `http` / `https`, or if the scheme is
 *   otherwise unsupported (null, custom scheme Kompressor does not resolve).
 */
public fun MediaSource.Companion.of(url: NSURL): MediaSource.Local =
    when (url.scheme) {
        "file" -> IosUrlMediaSource(url)
        // Bad-scheme branches funnel through [rejectScheme] (Nothing-returning) rather than
        // `require(false) { ... }`: the latter has static return type Unit, which would unify
        // the expression-bodied `when` at `Any` and break the `MediaSource.Local` return type.
        // The rejection message string is the cross-platform invariant consumed by the Android
        // sibling (CRA-93) — keep byte-identical via [MediaSourceRejections.REMOTE_URL_INPUT].
        "http", "https" -> rejectScheme(MediaSourceRejections.REMOTE_URL_INPUT)
        null -> rejectScheme("Unsupported NSURL scheme: <null>. Expected 'file'.")
        else -> rejectScheme("Unsupported NSURL scheme: ${url.scheme}. Expected 'file'.")
    }

/**
 * Create a [MediaSource] from a PhotoKit [PHAsset].
 *
 * Kompressor resolves the asset via `PHImageManager.requestAVAssetForVideo` (video / audio) or
 * `PHImageManager.requestImageDataAndOrientationForAsset` (images) at compression time. If the
 * asset is stored in iCloud and not locally available the behaviour depends on
 * [allowNetworkAccess]:
 *
 *  - **`true` (default)** — permit iCloud download. Compression blocks until the download
 *    completes (seconds to minutes depending on asset size + network). This matches the
 *    typical picker UX where consumers expect "just works".
 *  - **`false`** — iCloud-only assets fail with
 *    [co.crackn.kompressor.image.ImageCompressionError.SourceNotFound] /
 *    [co.crackn.kompressor.audio.AudioCompressionError.SourceNotFound] /
 *    [co.crackn.kompressor.video.VideoCompressionError.SourceNotFound]. Appropriate for background
 *    tasks, airplane mode, or bandwidth-sensitive contexts where the caller prefers a typed error
 *    to a surprise long-running download.
 *
 * @param asset The PhotoKit asset to compress.
 * @param allowNetworkAccess If `true` (default), permits iCloud download when the asset is not
 *   locally cached. Set to `false` to fail fast on iCloud-only assets.
 * @return A [MediaSource.Local] that the iOS compressors recognise as a PHAsset source.
 */
public fun MediaSource.Companion.of(
    asset: PHAsset,
    allowNetworkAccess: Boolean = true,
): MediaSource.Local = IosPHAssetMediaSource(asset, allowNetworkAccess)

/**
 * Create a [MediaSource] from in-memory [NSData].
 *
 * Safe for images up to ~50 MB; for images the iOS compressor decodes directly via
 * `CGImageSourceCreateWithData` (zero-copy when the [NSData] is mmap-backed), avoiding any temp
 * file.
 *
 * **For video / audio, prefer [NSURL] or [PHAsset]** — large NSData payloads force the whole
 * clip into memory and cause OOM on mid-range devices. NSData → temp file materialization for
 * audio / video is scoped to CRA-95 (Stream / Bytes dispatch); passing an [NSData] to
 * `AudioCompressor.compress` / `VideoCompressor.compress` today raises
 * [UnsupportedOperationException] pointing at CRA-95.
 *
 * @param data The in-memory buffer backing the source. Must remain valid for the lifetime of the
 *   `compress()` call.
 * @return A [MediaSource.Local] that the iOS compressors recognise as an NSData source.
 */
public fun MediaSource.Companion.of(data: NSData): MediaSource.Local = IosDataMediaSource(data)

/**
 * Create a [MediaSource] from an [NSInputStream].
 *
 * The stream is wrapped in an [okio.Source] via the iOS-native [asOkioSource] adapter, then
 * routed through the common [MediaSource.Local.Stream] pipeline — for video / audio the stream
 * is materialized to a private temp file, for images the stream is decoded directly. Stream
 * materialization for video / audio is scoped to CRA-95; invoking the audio / video compressors
 * with this source today raises [UnsupportedOperationException] pointing at CRA-95.
 *
 * @param stream The input stream to read from. Opened lazily by the adapter if not already open.
 * @param closeOnFinish If `true` (default), Kompressor closes the stream at the end of
 *   compression (success or failure). Set to `false` when the stream lifecycle is externally
 *   managed (e.g. shared with an uploader running in parallel).
 * @return A [MediaSource.Local.Stream] wrapping the input.
 */
public fun MediaSource.Companion.of(
    stream: NSInputStream,
    closeOnFinish: Boolean = true,
): MediaSource.Local = MediaSource.Local.Stream(stream.asOkioSource(), closeOnFinish = closeOnFinish)

/**
 * Raise [IllegalArgumentException] with [message] and a `Nothing` return type, so the throw-site
 * unifies cleanly inside expression-bodied `when` arms. Direct `throw IllegalArgumentException(...)`
 * is blocked by detekt's `TooGenericExceptionThrown` rule project-wide (see
 * `config/detekt/detekt.yml`); the targeted `@Suppress` is scoped to this one helper so the rest
 * of the file still benefits from the lint. Sibling of Android's
 * `AndroidMediaSources.rejectScheme`.
 */
@Suppress("TooGenericExceptionThrown")
private fun rejectScheme(message: String): Nothing = throw IllegalArgumentException(message)

/**
 * Internal marker wrapper for `file://` NSURL inputs. The dispatch in [toIosInputPath] unwraps
 * it back to a filesystem path the iOS compressors' private
 * `compressFilePath(inputPath, outputPath, ...)` helpers already know how to handle.
 */
internal class IosUrlMediaSource(val url: NSURL) : MediaSource.Local {
    override fun equals(other: Any?): Boolean = other is IosUrlMediaSource && other.url == url
    override fun hashCode(): Int = url.hashCode()
    override fun toString(): String = "IosUrlMediaSource(url=$url)"
}

/**
 * Internal marker wrapper for PhotoKit [PHAsset] inputs. Resolved via
 * `PHImageManager.requestAVAssetForVideo` (or, for images, `requestImageDataAndOrientationForAsset`)
 * at compression time — see [PHAssetResolver] for the coroutine-cancellable wrapper.
 *
 * `equals` / `hashCode` compare by `localIdentifier` (stable PhotoKit UUID) + [allowNetworkAccess]
 * rather than by object identity, so two [IosPHAssetMediaSource] wrappers minted from separate
 * `PHAsset.fetchAssetsWithLocalIdentifiers:` calls for the same underlying asset compare equal.
 */
internal class IosPHAssetMediaSource(
    val asset: PHAsset,
    val allowNetworkAccess: Boolean,
) : MediaSource.Local {
    override fun equals(other: Any?): Boolean =
        other is IosPHAssetMediaSource &&
            other.asset.localIdentifier == asset.localIdentifier &&
            other.allowNetworkAccess == allowNetworkAccess

    override fun hashCode(): Int =
        asset.localIdentifier.hashCode() * HASH_MULT + allowNetworkAccess.hashCode()

    override fun toString(): String =
        "IosPHAssetMediaSource(asset=${asset.localIdentifier}, allowNetworkAccess=$allowNetworkAccess)"

    private companion object {
        const val HASH_MULT = 31
    }
}

/**
 * Internal marker wrapper for [NSData] inputs. Used primarily for images (decoded via
 * `CGImageSourceCreateWithData` — zero-copy when the NSData is mmap-backed). For audio / video
 * the dispatch raises [UnsupportedOperationException] pointing at CRA-95.
 *
 * `equals` / `hashCode` compare by content via `NSData.isEqual` and `NSData.hash` — consistent
 * with [MediaSource.Local.Bytes]' content-based equality on the commonMain side.
 */
internal class IosDataMediaSource(val data: NSData) : MediaSource.Local {
    override fun equals(other: Any?): Boolean = other is IosDataMediaSource && other.data.isEqual(data)
    override fun hashCode(): Int = data.hash.toInt()
    override fun toString(): String = "IosDataMediaSource(length=${data.length})"
}

// `NSInputStream.asOkioSource()` — chunked-read okio.Source adapter lives in NSInputStreamSource.ios.kt.
