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
     * Compress an audio file from a [MediaSource] to a [MediaDestination].
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
     * @param config Compression settings (codec, bitrate, sample rate, channels).
     * @param onProgress Called with a [CompressionProgress] reflecting the current phase and
     *   per-phase fraction in `[0.0, 1.0]`. Fraction resets at each phase transition.
     * @return [Result] wrapping [CompressionResult] on success, or an [AudioCompressionError]
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
        config: AudioCompressionConfig = AudioCompressionConfig(),
        onProgress: suspend (CompressionProgress) -> Unit = {},
    ): Result<CompressionResult>

    /**
     * Extract a peak waveform from [input] — each returned float is the maximum absolute
     * amplitude observed in one time bucket, normalized to `[0f, 1f]`.
     *
     * **Output format — "absolute peaks" (mobile-UI convention).** The returned [FloatArray]
     * follows the convention used by every major mobile waveform renderer (`DSWaveformImage`
     * iOS, `Amplituda` / `compose-audiowaveform` / `WaveformSeekBar` Android, WhatsApp voice
     * notes, Apple Podcasts scrub bars, Telegram, Signal). Each element is computed as
     * `max(|sample|) / 32_768f` across the samples in one bucket, where `32_768` is the
     * full-scale value for 16-bit signed PCM. The result is guaranteed non-negative and
     * clamped to `[0f, 1f]`.
     *
     * Signed PCM `[-1f, 1f]` (pro-audio / Web Audio API convention) is **not** what this
     * API returns — the sign is eliminated by `abs()` when building the bucket peak.
     *
     * Directly usable by `DSWaveformImage` (iOS). For Android libraries that expect integer
     * amplitudes (`compose-audiowaveform`, `Amplituda`) map the array before passing it in:
     *
     * ```kotlin
     * val peaks: FloatArray = kompressor.audio.waveform(source).getOrThrow()
     * // compose-audiowaveform expects List<Int>:
     * val amplitudes: List<Int> = peaks.map { (it * 1000).toInt() }
     * // custom Canvas drawing: peaks[i] * canvasHeight
     * ```
     *
     * **Memory invariant.** The implementation streams PCM chunk-by-chunk and never holds
     * more than ~64 KB of decoded audio in memory, regardless of source duration. A 1-hour
     * podcast in MP3 (≈60 MB compressed, ≈608 MB decoded) is safe to waveform on-device.
     *
     * **Progress.** [onProgress] emits [CompressionProgress] with phase
     * [CompressionProgress.Phase.COMPRESSING] only — there is no `MATERIALIZING_INPUT` when
     * [input] is already a local file, and no `FINALIZING_OUTPUT` because the result is an
     * in-memory [FloatArray] with no sink.
     *
     * **Track selection.** Always operates on the **first** audio track of the source. Support
     * for an `audioTrackIndex` parameter is a future extension (see design doc §4.6).
     *
     * Cancel the calling coroutine scope to abort the extraction (structured concurrency).
     *
     * @param input Source media — see [MediaSource]. A source with no audio track (e.g. a
     *   video-only MP4 or an image file) surfaces as
     *   [Result.failure] wrapping [AudioCompressionError.NoAudioTrack].
     * @param targetSamples Number of peak values to return. 200 is typical for UI waveforms;
     *   10 000 is the rough upper bound for a high-detail scrub bar. Must be positive —
     *   `0` or negative values fail with [IllegalArgumentException] wrapped in [Result.failure].
     * @param onProgress Called with a [CompressionProgress] reflecting the
     *   [CompressionProgress.Phase.COMPRESSING] fraction in `[0f, 1f]` as PCM buckets are
     *   processed. Emission frequency is coerced to avoid flooding the callback for very
     *   large [targetSamples].
     * @return [Result] wrapping a [FloatArray] of size at most [targetSamples]. Every element
     *   is in `[0f, 1f]`. The array **may be shorter** than [targetSamples] when the source
     *   is shorter than one bucket's duration (or when the final bucket has no samples).
     *   On failure the [Result] wraps an [AudioCompressionError] subtype — notably
     *   [AudioCompressionError.NoAudioTrack] when the source has no audio.
     */
    public suspend fun waveform(
        input: MediaSource,
        targetSamples: Int = 200,
        onProgress: suspend (CompressionProgress) -> Unit = {},
    ): Result<FloatArray>
}
