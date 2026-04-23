/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.ExperimentalKompressorApi
import co.crackn.kompressor.image.ImageFormat
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

    /**
     * Extract a still frame from [input] at [atMillis] offset and encode it to [output] as an
     * image in the requested [format].
     *
     * **Rotation:** the source's natural orientation is preserved — a portrait recording
     * produces a portrait thumbnail. Android applies the video track's rotation tag while
     * decoding via `MediaMetadataRetriever`; iOS sets
     * `AVAssetImageGenerator.appliesPreferredTrackTransform = true` so the generator honours
     * the track's `preferredTransform`. Callers should assert on *displayed* dimensions
     * (portrait → `height > width`) rather than on the raw decoder buffer.
     *
     * **Keyframe selection:** both platforms default to the nearest keyframe (Android's
     * `OPTION_CLOSEST_SYNC`, iOS's 100 ms after-tolerance) — fast and memory-cheap. Exact
     * frame-accurate extraction is out of scope for this release.
     *
     * **Error types.** Failures surface as [VideoCompressionError] subtypes in `Result.failure`:
     *  - [VideoCompressionError.TimestampOutOfRange] when `atMillis > duration`,
     *  - [VideoCompressionError.DecodingFailed] when the frame at the requested offset can't be
     *    extracted (valid offset, codec / container issue),
     *  - [VideoCompressionError.SourceNotFound] / [VideoCompressionError.IoFailed] for the
     *    resolver and I/O paths shared with [compress].
     *
     * Requesting `atMillis < 0`, `quality !in 0..100`, or `maxDimension <= 0` is a programmer
     * error and throws [IllegalArgumentException] synchronously — these aren't recoverable
     * runtime states.
     *
     * @param input Source video — see [MediaSource] and platform-specific builders.
     * @param output Destination for the still image — see [MediaDestination].
     * @param atMillis Offset from the start of the video, in milliseconds. `0` extracts a frame
     *   from the very beginning. Must be `>= 0` and `<= duration`.
     * @param maxDimension Optional downscale target applied to the longer edge. `null` keeps
     *   the frame's native (oriented) resolution. On Android API 27+ the downscale happens
     *   during decode (`getScaledFrameAtTime`) so peak heap stays bounded on 1080p+ sources.
     * @param format Output image format. Availability follows [ImageFormat] — HEIC is iOS-only,
     *   AVIF requires Android API 34+ / iOS 16+, WebP is Android-only.
     * @param quality Lossy encoder quality, 0..100.
     * @return [Result] wrapping [CompressionResult] on success where `inputSize` is the source
     *   video's byte count and `outputSize` is the encoded still's byte count.
     */
    @ExperimentalKompressorApi
    // LongParameterList suppressed: every parameter is an orthogonal caller concern (source,
    // destination, offset, downscale cap, encoder format, encoder quality) with well-defined
    // defaults. Wrapping them in a config object would mirror the public API with no added
    // type safety and diverge from `compress()`'s flat signature.
    @Suppress("LongParameterList")
    public suspend fun thumbnail(
        input: MediaSource,
        output: MediaDestination,
        atMillis: Long = 0L,
        maxDimension: Int? = null,
        format: ImageFormat = ImageFormat.JPEG,
        quality: Int = 80,
    ): Result<CompressionResult>
}
