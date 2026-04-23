/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.property

import co.crackn.kompressor.audio.PcmPeakBucketer
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

private const val SEED = 0x50_63_6d_42L // "PcmB"

/**
 * Property tests for [PcmPeakBucketer]. Every produced peak must stay in `[0f, 1f]` regardless
 * of the byte content pumped in — this is the public-contract guarantee of
 * [co.crackn.kompressor.audio.AudioCompressor.waveform] and the reason we normalize by
 * `32_768f` (not `32_767f`).
 */
@OptIn(ExperimentalKotest::class)
class PcmPeakBucketerPropertyTest {

    private val propConfig = PropTestConfig(seed = SEED)

    @Test
    fun peaksAlwaysInZeroOneRange() = runTest {
        checkAll(
            propConfig,
            Arb.byteArray(Arb.int(0..MAX_BYTES), Arb.byte()),
        ) { bytes ->
            val bucketer = PcmPeakBucketer(
                targetSamples = TARGET,
                totalDurationUs = DURATION_US,
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
            )
            bucketer.accept(bytes, 0, bytes.size)
            val peaks = bucketer.finish()
            peaks.forEach { p ->
                (p >= 0f) shouldBe true
                (p <= 1f) shouldBe true
            }
        }
    }

    @Test
    fun sizeNeverExceedsTargetSamples() = runTest {
        checkAll(
            propConfig,
            Arb.byteArray(Arb.int(0..MAX_BYTES), Arb.byte()),
            Arb.int(1..500),
        ) { bytes, target ->
            val bucketer = PcmPeakBucketer(
                targetSamples = target,
                totalDurationUs = DURATION_US,
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
            )
            bucketer.accept(bytes, 0, bytes.size)
            val peaks = bucketer.finish()
            (peaks.size <= target) shouldBe true
        }
    }

    private companion object {
        const val TARGET = 50
        const val DURATION_US = 1_000_000L
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 1
        const val MAX_BYTES = 4096
    }
}
