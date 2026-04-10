package co.crackn.kompressor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompressionResultTest {

    @Test
    fun createResult() {
        val result = CompressionResult(
            inputSize = 1_000_000L,
            outputSize = 250_000L,
            durationMs = 120L,
        )

        assertEquals(1_000_000L, result.inputSize)
        assertEquals(250_000L, result.outputSize)
        assertEquals(0.25f, result.compressionRatio)
        assertEquals(120L, result.durationMs)
    }

    @Test
    fun compressionRatioIsDerived() {
        val result = CompressionResult(
            inputSize = 1_000L,
            outputSize = 500L,
            durationMs = 10L,
        )

        assertEquals(0.5f, result.compressionRatio)
        assertTrue(result.compressionRatio < 1.0f)
    }

    @Test
    fun equalityByValue() {
        val a = CompressionResult(100L, 50L, 5L)
        val b = CompressionResult(100L, 50L, 5L)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequalityWhenFieldsDiffer() {
        val base = CompressionResult(100L, 50L, 5L)

        assertNotEquals(base, base.copy(inputSize = 200L))
        assertNotEquals(base, base.copy(outputSize = 99L))
        assertNotEquals(base, base.copy(durationMs = 999L))
    }

    @Test
    fun rejectsZeroInputSize() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(inputSize = 0L, outputSize = 50L, durationMs = 5L)
        }
    }

    @Test
    fun rejectsNegativeInputSize() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(inputSize = -1L, outputSize = 50L, durationMs = 5L)
        }
    }

    @Test
    fun rejectsNegativeOutputSize() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(inputSize = 100L, outputSize = -1L, durationMs = 5L)
        }
    }

    @Test
    fun copyPreservesUnchangedFields() {
        val original = CompressionResult(100L, 50L, 5L)
        val copied = original.copy(outputSize = 30L)

        assertEquals(original.inputSize, copied.inputSize)
        assertEquals(30L, copied.outputSize)
        assertEquals(0.3f, copied.compressionRatio)
        assertEquals(original.durationMs, copied.durationMs)
    }
}
