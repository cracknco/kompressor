/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource

/** Compresses image files. */
public interface ImageCompressor {

    /**
     * Compress an image from a [MediaSource] to a [MediaDestination].
     *
     * Unlike audio/video compression, image compression has no progress callback because the
     * underlying platform APIs are synchronous single-step operations with no intermediate state.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * Inputs and outputs cover every platform-native form via the [MediaSource] / [MediaDestination]
     * sealed hierarchies and their platform-specific builders — filesystem paths, Android `Uri`,
     * iOS `NSURL` / `PHAsset` / `NSData`, `InputStream` / `NSInputStream`, `ByteArray`, and
     * MediaStore / SAF output handles. See [docs/concepts/io-model.md](https://github.com/cracknco/kompressor/blob/main/docs/concepts/io-model.md)
     * for the full model, memory invariants, and `closeOnFinish` contract.
     *
     * @param input Source media — see [MediaSource] and platform-specific builders.
     * @param output Destination — see [MediaDestination] and platform-specific builders.
     * @param config Compression settings (format, quality, resize).
     * @return [Result] wrapping [CompressionResult] on success, or an [ImageCompressionError]
     *   subtype on failure. Possible failure types:
     *   [ImageCompressionError.UnsupportedSourceFormat],
     *   [ImageCompressionError.DecodingFailed],
     *   [ImageCompressionError.EncodingFailed],
     *   [ImageCompressionError.IoFailed],
     *   [ImageCompressionError.Unknown]. [IllegalArgumentException] may still be thrown
     *   synchronously for programmer-error configs (e.g. requesting a non-JPEG output format).
     *
     * **Thread-safety:** implementations are stateless and thread-safe. Concurrent `compress()`
     * calls from different coroutines or OS processes on the same instance are safe provided
     * every call writes to a distinct destination. Concurrent calls that share a destination
     * produce undefined results. See `docs/threading-model.md`.
     */
    public suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig = ImageCompressionConfig(),
    ): Result<CompressionResult>

    /**
     * Decode a thumbnail of [input] without loading the full-resolution bitmap into memory.
     *
     * Unlike [compress], which may decode the entire source and then downscale, `thumbnail`
     * uses the platform's **sampled-decode** primitives so peak heap usage scales with the
     * requested thumbnail size rather than the source's pixel count.
     *
     *  * Android — two-pass `BitmapFactory` with `inJustDecodeBounds = true` then
     *    `inSampleSize` (power-of-2), followed by an exact final resize when the sample-sized
     *    bitmap still exceeds [maxDimension].
     *  * iOS — `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceThumbnailMaxPixelSize
     *    = maxDimension` and `kCGImageSourceCreateThumbnailWithTransform = true` so EXIF
     *    rotation is applied by the decoder rather than via a second pass.
     *
     * A 48 MP photo (4000×3000) decoded to RGBA in full occupies ~48 MB. With
     * `maxDimension = 200`, sampled decode holds <1 MB in RAM — the consumer-facing reason
     * this method exists alongside [compress].
     *
     * Aspect ratio is preserved; the longer edge of the output never exceeds [maxDimension].
     * The method never upscales — when [maxDimension] is larger than both source dimensions
     * the output keeps the source pixel dimensions (re-encoded to [format] at [quality]).
     *
     * Inputs and outputs cover every platform-native form via the [MediaSource] /
     * [MediaDestination] sealed hierarchies — see [compress] for the full list.
     *
     * @param input Source media — see [MediaSource] and platform-specific builders.
     * @param output Destination — see [MediaDestination] and platform-specific builders.
     * @param maxDimension Largest dimension (width or height) in pixels. Must be strictly
     *   positive; `0` or negative values surface as `Result.failure(IllegalArgumentException)`
     *   without touching the source.
     * @param format Output image format.
     * @param quality Encoding quality, 0..100.
     * @return [Result] wrapping [CompressionResult] on success, or the same
     *   [ImageCompressionError] subtypes as [compress] on failure.
     */
    public suspend fun thumbnail(
        input: MediaSource,
        output: MediaDestination,
        maxDimension: Int,
        format: ImageFormat = ImageFormat.JPEG,
        quality: Int = 80,
    ): Result<CompressionResult>
}
