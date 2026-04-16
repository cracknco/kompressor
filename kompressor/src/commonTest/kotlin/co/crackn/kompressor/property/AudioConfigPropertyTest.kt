/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.property

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import io.kotest.property.Arb
import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKotest::class)
class AudioConfigPropertyTest {

    private val config = PropTestConfig(seed = SEED)

    @Test
    fun validBitrateAndSampleRateNeverThrow() = runTest {
        checkAll(
            config,
            Arb.int(1..320_000),
            Arb.int(1..192_000),
            Arb.element(AudioChannels.MONO, AudioChannels.STEREO),
        ) { bitrate, sampleRate, channels ->
            AudioCompressionConfig(
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
            )
        }
    }

    @Test
    fun nonPositiveBitrateThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { bitrate ->
            assertFailsWith<IllegalArgumentException> {
                AudioCompressionConfig(bitrate = bitrate)
            }
        }
    }

    @Test
    fun nonPositiveSampleRateThrows() = runTest {
        checkAll(config, Arb.int(-1000..0)) { sampleRate ->
            assertFailsWith<IllegalArgumentException> {
                AudioCompressionConfig(sampleRate = sampleRate)
            }
        }
    }

    @Test
    fun allChannelVariantsAccepted() {
        AudioChannels.entries.forEach { channels ->
            AudioCompressionConfig(channels = channels)
        }
    }

    private companion object {
        const val SEED = 12345L
    }
}
