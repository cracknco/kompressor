package co.crackn.kompressor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompressionResultTest {

    @Test
    fun createResult() {
        val result = CompressionResult(
            inputSize = 1_000_000L,
            outputSize = 250_000L,
            compressionRatio = 0.25f,
            durationMs = 120L,
        )

        assertEquals(1_000_000L, result.inputSize)
        assertEquals(250_000L, result.outputSize)
        assertEquals(0.25f, result.compressionRatio)
        assertEquals(120L, result.durationMs)
    }

    @Test
    fun compressionRatioBelowOneMeansSizeReduced() {
        val result = CompressionResult(
            inputSize = 1_000L,
            outputSize = 500L,
            compressionRatio = 0.5f,
            durationMs = 10L,
        )

        assertTrue(result.compressionRatio < 1.0f)
    }

    @Test
    fun equalityByValue() {
        val a = CompressionResult(100L, 50L, 0.5f, 5L)
        val b = CompressionResult(100L, 50L, 0.5f, 5L)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequalityWhenFieldsDiffer() {
        val base = CompressionResult(100L, 50L, 0.5f, 5L)

        assertNotEquals(base, base.copy(inputSize = 200L))
        assertNotEquals(base, base.copy(outputSize = 99L))
        assertNotEquals(base, base.copy(compressionRatio = 0.9f))
        assertNotEquals(base, base.copy(durationMs = 999L))
    }

    @Test
    fun copyPreservesUnchangedFields() {
        val original = CompressionResult(100L, 50L, 0.5f, 5L)
        val copied = original.copy(outputSize = 30L)

        assertEquals(original.inputSize, copied.inputSize)
        assertEquals(30L, copied.outputSize)
        assertEquals(original.compressionRatio, copied.compressionRatio)
        assertEquals(original.durationMs, copied.durationMs)
    }
}
