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
     * Compress an image file.
     *
     * Unlike audio/video compression, image compression has no progress callback because the
     * underlying platform APIs are synchronous single-step operations with no intermediate state.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * @param inputPath Absolute filesystem path to the source image.
     * @param outputPath Absolute filesystem path for the compressed output.
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
     * every call writes to a distinct output path. Concurrent calls that share an output path
     * produce undefined results. See `docs/threading-model.md`.
     */
    public suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig = ImageCompressionConfig(),
    ): Result<CompressionResult>

    /**
     * Compress an image from a rich [MediaSource] to a [MediaDestination].
     *
     * This is the modern signature, preferred over [compress] (the path-based overload) for any
     * consumer working with platform pickers (`Uri`, `NSURL`, `PHAsset`) or streams. The
     * path-based signature remains available but will be deprecated in a future release.
     *
     * Image compression is synchronous at the platform level — unlike [AudioCompressor.compress]
     * and [VideoCompressor.compress], there is **no `onProgress` callback**. Cancel the calling
     * coroutine scope to abort the compression (structured concurrency).
     *
     * In this release only [MediaSource.Local.FilePath] / [MediaDestination.Local.FilePath]
     * dispatch is implemented. All other variants (`Stream`, `Bytes`, and the platform-specific
     * `Uri` / `NSURL` / `PHAsset` / `NSData` builders that ship later) surface an
     * [UnsupportedOperationException] wrapped in [Result.failure], with a message citing the
     * Linear ticket that will implement them (CRA-93 for Android `Uri`, CRA-94 for iOS
     * `NSURL` / `PHAsset` / `NSData`, CRA-95 for `Stream` / `Bytes`).
     *
     * @param input Media source — see [MediaSource] and platform-specific builders.
     * @param output Media destination — see [MediaDestination] and platform-specific builders.
     * @param config Compression settings (format, quality, resize).
     * @return [Result] wrapping [CompressionResult] on success, or an [ImageCompressionError]
     *   subtype on failure. See the path-based overload for the base set of error types. Until
     *   CRA-95 lands, non-`FilePath` inputs/outputs surface an [UnsupportedOperationException].
     *
     * **Thread-safety:** same guarantees as the path-based overload. See `docs/threading-model.md`.
     */
    public suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: ImageCompressionConfig = ImageCompressionConfig(),
    ): Result<CompressionResult>
}
