/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

/**
 * Result of any compression operation.
 * Returned wrapped in [Result] — use `result.getOrThrow()` or `result.fold(...)`.
 *
 * **Note on output size:** Re-encoding an already-compressed file (especially JPEG)
 * can produce a *larger* output if the target quality is higher than the original's,
 * or if the source was already heavily optimised. Check [isSmallerThanOriginal] and
 * decide at the call site whether to keep the compressed file or discard it.
 */
data class CompressionResult(
    /** Size of the input file in bytes. */
    val inputSize: Long,
    /** Size of the compressed output file in bytes. */
    val outputSize: Long,
    /** Time taken for the compression in milliseconds. */
    val durationMs: Long,
) {
    init {
        require(inputSize > 0) { "inputSize must be positive, was $inputSize" }
        require(outputSize >= 0) { "outputSize must not be negative, was $outputSize" }
        require(durationMs >= 0) { "durationMs must not be negative, was $durationMs" }
    }

    /** Ratio of output/input size (< 1.0 means compression reduced size). */
    val compressionRatio: Float get() = outputSize.toFloat() / inputSize.toFloat()

    /** `true` when the compressed output is strictly smaller than the original input. */
    val isSmallerThanOriginal: Boolean get() = outputSize < inputSize
}
