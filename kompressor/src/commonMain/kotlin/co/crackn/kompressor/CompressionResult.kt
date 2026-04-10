package co.crackn.kompressor

/**
 * Result of any compression operation.
 * Returned wrapped in [Result] — use `result.getOrThrow()` or `result.fold(...)`.
 */
data class CompressionResult(
    /** Size of the input file in bytes. */
    val inputSize: Long,
    /** Size of the compressed output file in bytes. */
    val outputSize: Long,
    /** Time taken for the compression in milliseconds. */
    val durationMs: Long,
) {
    /** Ratio of output/input size (< 1.0 means compression reduced size). */
    val compressionRatio: Float get() = outputSize.toFloat() / inputSize.toFloat()
}
