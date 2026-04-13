package co.crackn.kompressor.property

import co.crackn.kompressor.CompressionResult
import io.kotest.property.Arb
import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKotest::class)
class CompressionResultPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun compressionRatioIsOutputOverInput() = runTest {
        checkAll(config, Arb.long(1L..1_000_000L), Arb.long(0L..1_000_000L), Arb.long(0L..10_000L)) {
                inputSize, outputSize, durationMs ->
            val result = CompressionResult(inputSize, outputSize, durationMs)
            val expected = outputSize.toFloat() / inputSize.toFloat()
            assertTrue(
                result.compressionRatio == expected,
                "Expected ratio $expected, got ${result.compressionRatio}",
            )
        }
    }

    @Test
    fun isSmallerThanOriginalCorrect() = runTest {
        checkAll(config, Arb.long(1L..1_000_000L), Arb.long(0L..1_000_000L), Arb.long(0L..10_000L)) {
                inputSize, outputSize, durationMs ->
            val result = CompressionResult(inputSize, outputSize, durationMs)
            assertTrue(
                result.isSmallerThanOriginal == (outputSize < inputSize),
                "isSmallerThanOriginal should be ${outputSize < inputSize} for " +
                    "input=$inputSize, output=$outputSize",
            )
        }
    }

    @Test
    fun validInputsNeverThrow() = runTest {
        checkAll(config, Arb.long(1L..1_000_000L), Arb.long(0L..1_000_000L), Arb.long(0L..10_000L)) {
                inputSize, outputSize, durationMs ->
            CompressionResult(inputSize, outputSize, durationMs)
        }
    }

    @Test
    fun zeroInputSizeThrows() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(inputSize = 0, outputSize = 100, durationMs = 10)
        }
    }

    @Test
    fun negativeOutputSizeThrows() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(inputSize = 100, outputSize = -1, durationMs = 10)
        }
    }

    @Test
    fun negativeDurationThrows() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(inputSize = 100, outputSize = 50, durationMs = -1)
        }
    }

    private companion object {
        val SEED: Long = kotlin.random.Random.nextLong().also {
            println("[property-seed] CompressionResultPropertyTest: $it")
        }
    }
}
