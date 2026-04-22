/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource

/**
 * Compresses video files.
 *
 * **Rotation:** source orientation is preserved — a portrait recording stays portrait, a
 * landscape recording stays landscape. The encoding strategy differs per platform: Android's
 * Media3 `Transformer` applies the source rotation inside its frame-processor, which may
 * bake the rotation into the pixels and zero the tag or keep the tag intact (players render
 * both identically). On iOS, `AVAssetExportSession` copies `preferredTransform` natively,
 * and the custom `AVAssetWriter` path forwards `preferredTransform` onto the writer input.
 * Callers should assert on *displayed* dimensions rather than the raw tag when writing
 * orientation-sensitive tests.
 */
public interface VideoCompressor {

    /**
     * Video codec MIME types this device can decode from. The set reflects the
     * platform's reported decoder capabilities at the moment of the first read
     * and is stable for the lifetime of this instance; a missing entry does not
     * guarantee the decoder can't be loaded at runtime, it just means the
     * implementation didn't detect it.
     *
     * Defaults to an empty set for forward compatibility; implementations
     * override to declare what they actually support.
     */
    public val supportedInputFormats: Set<String> get() = emptySet()

    /**
     * Video codec MIME types this device can encode to. See
     * [supportedInputFormats] for the semantics.
     */
    public val supportedOutputFormats: Set<String> get() = emptySet()

    /**
     * Compress a video file from a [MediaSource] to a [MediaDestination].
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * Failures surface as [VideoCompressionError] subtypes wrapped in [Result.failure]:
     * callers can `when`-branch on the concrete type to show actionable UI.
     *
     * Progress emission surfaces as [CompressionProgress] updates via [onProgress] — possible
     * phases:
     *  - `Phase.MATERIALIZING_INPUT` while a non-file input (Stream / Bytes / Uri / PHAsset) is
     *    materialized to a local temp file,
     *  - `Phase.COMPRESSING` during active transcoding,
     *  - `Phase.FINALIZING_OUTPUT` while the temp file is committed to the requested destination
     *    (MediaStore, consumer Sink, etc.), terminated by `Phase.FINALIZING_OUTPUT(1f)` on success.
     *
     * On failure, the last emission a consumer sees is the most recent `Phase.COMPRESSING`
     * fraction — `Phase.FINALIZING_OUTPUT(1f)` is emitted only after the inner pipeline has
     * succeeded and the output has been committed. Consumer UIs keying on `FINALIZING_OUTPUT(1f)`
     * as the terminal-success signal are therefore safe; UIs wanting to reset on failure should
     * key on the surrounding `Result.isFailure`.
     *
     * Inputs and outputs cover every platform-native form via the [MediaSource] / [MediaDestination]
     * sealed hierarchies. See [docs/concepts/io-model.md](https://github.com/cracknco/kompressor/blob/main/docs/concepts/io-model.md)
     * for the full model, memory invariants, and `closeOnFinish` contract.
     *
     * @param input Source media — see [MediaSource] and platform-specific builders.
     * @param output Destination — see [MediaDestination] and platform-specific builders.
     * @param config Compression settings (codec, resolution, bitrate, etc.).
     * @param onProgress Called with a [CompressionProgress] reflecting the current phase and
     *   per-phase fraction in `[0.0, 1.0]`. Fraction resets at each phase transition.
     * @return [Result] wrapping [CompressionResult] on success, or a [VideoCompressionError]
     *   subtype on failure.
     *
     * **Thread-safety:** implementations are stateless and thread-safe. Concurrent `compress()`
     * calls from different coroutines or OS processes on the same instance are safe provided
     * every call writes to a distinct destination. Concurrent calls that share a destination
     * produce undefined results. See `docs/threading-model.md`.
     */
    public suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: VideoCompressionConfig = VideoCompressionConfig(),
        onProgress: suspend (CompressionProgress) -> Unit = {},
    ): Result<CompressionResult>
}
