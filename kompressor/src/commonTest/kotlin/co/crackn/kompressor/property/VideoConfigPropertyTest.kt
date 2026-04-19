/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.property

import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.property.Arb
import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKotest::class)
class VideoConfigPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun validParametersNeverThrow() = runTest {
        checkAll(
            config,
            Arb.int(1..10_000_000),
            Arb.int(1..320_000),
            Arb.int(1..120),
            Arb.int(1..10),
        ) { videoBitrate, audioBitrate, maxFrameRate, keyFrameInterval ->
            VideoCompressionConfig(
                videoBitrate = videoBitrate,
                audioBitrate = audioBitrate,
                maxFrameRate = maxFrameRate,
                keyFrameInterval = keyFrameInterval,
            )
        }
    }

    @Test
    fun nonPositiveVideoBitrateThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { videoBitrate ->
            assertFailsWith<IllegalArgumentException> {
                VideoCompressionConfig(videoBitrate = videoBitrate)
            }
        }
    }

    @Test
    fun nonPositiveAudioBitrateThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { audioBitrate ->
            assertFailsWith<IllegalArgumentException> {
                VideoCompressionConfig(audioBitrate = audioBitrate)
            }
        }
    }

    @Test
    fun nonPositiveMaxFrameRateThrows() = runTest {
        checkAll(config, Arb.int(-100..0)) { maxFrameRate ->
            assertFailsWith<IllegalArgumentException> {
                VideoCompressionConfig(maxFrameRate = maxFrameRate)
            }
        }
    }

    @Test
    fun nonPositiveKeyFrameIntervalThrows() = runTest {
        checkAll(config, Arb.int(-100..0)) { keyFrameInterval ->
            assertFailsWith<IllegalArgumentException> {
                VideoCompressionConfig(keyFrameInterval = keyFrameInterval)
            }
        }
    }

    @Test
    fun customResolutionValidation() = runTest {
        checkAll(config, Arb.int(1..10_000)) { maxShortEdge ->
            MaxResolution.Custom(maxShortEdge)
        }
    }

    @Test
    fun customResolutionNonPositiveThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { maxShortEdge ->
            assertFailsWith<IllegalArgumentException> {
                MaxResolution.Custom(maxShortEdge)
            }
        }
    }

    private companion object {
        const val SEED = 12345L
    }
}
