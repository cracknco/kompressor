/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import co.crackn.kompressor.CompressionResult

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
     * @param inputPath Absolute filesystem path to the source audio.
     * @param outputPath Absolute filesystem path for the compressed output.
     * @param config Compression settings (codec, bitrate, sample rate, channels).
     * @param onProgress Called with a value between 0.0 and 1.0 as compression progresses.
     * @return [Result] wrapping [CompressionResult] on success, or an [AudioCompressionError] on failure.
     */
    public suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig = AudioCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
