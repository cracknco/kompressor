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
}
