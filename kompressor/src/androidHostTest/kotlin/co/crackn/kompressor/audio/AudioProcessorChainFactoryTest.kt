/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Host tests for [planAudioProcessors]. These are the unit tests that replace the coverage
 * previously provided by `PcmProcessorTest` / `PcmRingBufferTest` — the PCM plumbing itself is
 * now Media3's responsibility, but the *decision* of which processors to wire up is ours.
 *
 * We exercise the plan, not the materialised [androidx.media3.common.audio.AudioProcessor]s,
 * because their constructors pull in `android.util.SparseArray` which does not exist on the
 * host JVM. The plan → processor step is trivial and exercised by device tests.
 */
class AudioProcessorChainFactoryTest {

    private val config44kStereo = AudioCompressionConfig(
        sampleRate = 44_100,
        channels = AudioChannels.STEREO,
    )

    private val config22kMono = AudioCompressionConfig(
        sampleRate = 22_050,
        channels = AudioChannels.MONO,
    )

    @Test
    fun exactMatchReturnsEmptyPlanSoMedia3CanPassthrough() {
        val plan = planAudioProcessors(
            inputSampleRate = 44_100,
            inputChannels = 2,
            config = config44kStereo,
        )
        plan.isEmpty shouldBe true
        plan.channelMixing.shouldBeNull()
        plan.resampleToHz.shouldBeNull()
    }

    @Test
    fun differentSampleRateSameChannelsPlansOnlySonic() {
        val plan = planAudioProcessors(
            inputSampleRate = 48_000,
            inputChannels = 2,
            config = config44kStereo,
        )
        plan.channelMixing.shouldBeNull()
        plan.resampleToHz shouldBe 44_100
    }

    @Test
    fun sameSampleRateDifferentChannelsPlansOnlyChannelMixing() {
        val plan = planAudioProcessors(
            inputSampleRate = 44_100,
            inputChannels = 2,
            config = config44kStereo.copy(channels = AudioChannels.MONO),
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 1
        plan.resampleToHz.shouldBeNull()
    }

    @Test
    fun differentSampleRateAndChannelsPlansBoth() {
        val plan = planAudioProcessors(
            inputSampleRate = 48_000,
            inputChannels = 2,
            config = config22kMono,
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 1
        plan.resampleToHz shouldBe 22_050
    }

    @Test
    fun nullInputSampleRateForcesSonicInThePlan() {
        val plan = planAudioProcessors(
            inputSampleRate = null,
            inputChannels = 2,
            config = config44kStereo,
        )
        plan.resampleToHz shouldBe 44_100
    }

    @Test
    fun nullInputChannelsForcesChannelMixingInThePlan() {
        val plan = planAudioProcessors(
            inputSampleRate = 44_100,
            inputChannels = null,
            config = config44kStereo,
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 2
    }

    @Test
    fun nullInputSampleRateAndChannelsForcesBoth() {
        val plan = planAudioProcessors(
            inputSampleRate = null,
            inputChannels = null,
            config = config22kMono,
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 1
        plan.resampleToHz shouldBe 22_050
    }

    @Test
    fun monotomonoSameRateShortcircuitsToEmptyPlan() {
        val plan = planAudioProcessors(
            inputSampleRate = 22_050,
            inputChannels = 1,
            config = config22kMono,
        )
        plan.isEmpty shouldBe true
    }

    @Test
    fun monoInputAtDifferentRatePlansOnlySonicWhenTargetIsMono() {
        val plan = planAudioProcessors(
            inputSampleRate = 48_000,
            inputChannels = 1,
            config = config22kMono,
        )
        // Channels match (1==1) so no ChannelMixing; only Sonic for the sample-rate conversion.
        plan.channelMixing.shouldBeNull()
        plan.resampleToHz shouldBe 22_050
    }
}
