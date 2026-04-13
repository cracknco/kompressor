package co.crackn.kompressor.audio

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Host-side coverage for the specific combination required by the §4 robustness scenario: a
 * 48 kHz stereo source compressed to 22.05 kHz mono. Asserts the plan contains *both* a Sonic
 * (resample) step and a ChannelMixing (downmix) step so the plan → processors materialisation
 * in `AudioProcessorPlan.toProcessors` will actually wire both up.
 *
 * NOTE: `planAudioProcessors` is `internal` and lives in `androidMain`, so this test lives in
 * `androidHostTest` — it cannot move into `commonTest` where the task spec initially placed it.
 * The behaviour it exercises is platform-agnostic; the placement is a Kotlin/Multiplatform
 * visibility constraint only.
 */
class SonicAndChannelMixingPlanTest {

    @Test
    fun plan48kStereoTo22kMonoContainsBothSonicAndChannelMixing() {
        val plan = planAudioProcessors(
            inputSampleRate = 48_000,
            inputChannels = 2,
            config = AudioCompressionConfig(
                sampleRate = 22_050,
                channels = AudioChannels.MONO,
            ),
        )

        plan.isEmpty shouldBe false
        val mixing = plan.channelMixing
        mixing.shouldNotBeNull()
        mixing.outputChannelCount shouldBe 1
        plan.resampleToHz shouldBe 22_050
    }
}
