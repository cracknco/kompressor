/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.property

import co.crackn.kompressor.image.ImageCompressionConfig
import io.kotest.property.Arb
import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKotest::class)
class ImageConfigPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun validQualityAndDimensionsNeverThrow() = runTest {
        checkAll(config, Arb.int(0..100), Arb.int(1..10_000), Arb.int(1..10_000)) {
                quality, maxWidth, maxHeight ->
            ImageCompressionConfig(
                quality = quality,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            )
        }
    }

    @Test
    fun qualityBelowZeroThrows() = runTest {
        checkAll(config, Arb.int(-1000..-1)) { quality ->
            assertFailsWith<IllegalArgumentException> {
                ImageCompressionConfig(quality = quality)
            }
        }
    }

    @Test
    fun qualityAbove100Throws() = runTest {
        checkAll(config, Arb.int(101..1000)) { quality ->
            assertFailsWith<IllegalArgumentException> {
                ImageCompressionConfig(quality = quality)
            }
        }
    }

    @Test
    fun nonPositiveMaxWidthThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { maxWidth ->
            assertFailsWith<IllegalArgumentException> {
                ImageCompressionConfig(maxWidth = maxWidth)
            }
        }
    }

    @Test
    fun nonPositiveMaxHeightThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { maxHeight ->
            assertFailsWith<IllegalArgumentException> {
                ImageCompressionConfig(maxHeight = maxHeight)
            }
        }
    }

    @Test
    fun nullDimensionsAlwaysSucceed() = runTest {
        checkAll(config, Arb.int(0..100)) { quality ->
            ImageCompressionConfig(quality = quality, maxWidth = null, maxHeight = null)
        }
    }

    private companion object {
        const val SEED = 12345L
    }
}
