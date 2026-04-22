/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource

/** Compresses audio files. */
public interface AudioCompressor {

    /**
     * Audio codec MIME types this device can decode from. The set reflects the platform's
     * reported decoder capabilities at the moment of the first read and is stable for the
     * lifetime of this instance; a missing entry does not guarantee the decoder can't be
     * loaded at runtime, it just means the implementation didn't detect it.
     *
     * Defaults to an empty set for forward compatibility; implementations override to declare
     * what they actually support.
     */
    public val supportedInputFormats: Set<String> get() = emptySet()

    /**
     * Audio codec MIME types this device can encode to. See [supportedInputFormats] for the
     * semantics.
     */
    public val supportedOutputFormats: Set<String> get() = emptySet()

    /**
     * Compress an audio file.
     *
     * Cancel the calling coroutine scope to abort the compression (structured concurrency).
     *
     * Failures surface as [AudioCompressionError] subtypes wrapped in [Result.failure]: callers
     * can `when`-branch on the concrete type to show actionable UI.
     *
     * Contract violations on [config] (programmer error, e.g. passing a non-AAC
     * [AudioCompressionConfig.codec] on an implementation that only supports AAC) surface as
     * an [IllegalArgumentException] wrapped in [Result.failure] rather than the typed
     * [AudioCompressionError] hierarchy. This mirrors the Kotlin convention for pre-condition
     * failures and is stable across platforms — audit tracked under CRA-21.
     *
     * @param inputPath Absolute filesystem path to the source audio.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (codec, bitrate, sample rate, channels).
     * @param onProgress Called with a value between 0.0 and 1.0 as compression progresses.
     * @return [Result] wrapping [CompressionResult] on success, or an [AudioCompressionError] on failure.
     *         [IllegalArgumentException] may be wrapped in [Result.failure] for programmer-error
     *         configs (e.g. requesting a non-AAC codec).
     *
     * **Thread-safety:** implementations are stateless and thread-safe. Concurrent `compress()`
     * calls from different coroutines or OS processes on the same instance are safe provided
     * every call writes to a distinct output path. Concurrent calls that share an output path
     * produce undefined results. See `docs/threading-model.md`.
     */
    public suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig = AudioCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>

    /**
     * Compress an audio file from a rich [MediaSource] to a [MediaDestination].
     *
     * See [ImageCompressor.compress] for the rationale of the rich-source overload. Audio
     * compression emits [CompressionProgress] updates via [onProgress] — `Phase.COMPRESSING`
     * during active transcoding, terminated by `Phase.FINALIZING_OUTPUT(1f)` on success.
     * `Phase.MATERIALIZING_INPUT` will be added when CRA-95 wires up stream / bytes inputs
     * (CRA-93 for Android `Uri`, CRA-94 for iOS `NSURL` / `PHAsset` / `NSData`); until then
     * non-`FilePath` inputs throw [UnsupportedOperationException] at dispatch time.
     *
     * On failure, the last emission a consumer sees is the most recent `Phase.COMPRESSING`
     * fraction — `Phase.FINALIZING_OUTPUT` is emitted only after the inner pipeline has
     * succeeded. Consumer UIs keying on `FINALIZING_OUTPUT(1f)` as the terminal-success signal
     * are therefore safe; UIs wanting to reset on failure should key on the surrounding
     * `Result.isFailure`.
     *
     * @param input Media source — see [MediaSource] and platform-specific builders.
     * @param output Media destination — see [MediaDestination] and platform-specific builders.
     * @param config Compression settings (codec, bitrate, sample rate, channels).
     * @param onProgress Called with a [CompressionProgress] reflecting the current phase and
     *   per-phase fraction in `[0.0, 1.0]`. Fraction resets at each phase transition.
     * @return [Result] wrapping [CompressionResult] on success, or an [AudioCompressionError]
     *   subtype on failure. Until CRA-95 lands, non-`FilePath` inputs/outputs surface an
     *   [UnsupportedOperationException].
     *
     * **Thread-safety:** same guarantees as the path-based overload. See `docs/threading-model.md`.
     */
    public suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: AudioCompressionConfig = AudioCompressionConfig(),
        onProgress: suspend (CompressionProgress) -> Unit = {},
    ): Result<CompressionResult>
}
