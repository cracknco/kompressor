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
    fun `exact match returns empty plan so Media3 can passthrough`() {
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
    fun `different sample rate, same channels plans only Sonic`() {
        val plan = planAudioProcessors(
            inputSampleRate = 48_000,
            inputChannels = 2,
            config = config44kStereo,
        )
        plan.channelMixing.shouldBeNull()
        plan.resampleToHz shouldBe 44_100
    }

    @Test
    fun `same sample rate, different channels plans only ChannelMixing`() {
        val plan = planAudioProcessors(
            inputSampleRate = 44_100,
            inputChannels = 2,
            config = config44kStereo.copy(channels = AudioChannels.MONO),
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 1
        plan.resampleToHz.shouldBeNull()
    }

    @Test
    fun `different sample rate and channels plans both`() {
        val plan = planAudioProcessors(
            inputSampleRate = 48_000,
            inputChannels = 2,
            config = config22kMono,
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 1
        plan.resampleToHz shouldBe 22_050
    }

    @Test
    fun `null input sample rate forces Sonic in the plan`() {
        val plan = planAudioProcessors(
            inputSampleRate = null,
            inputChannels = 2,
            config = config44kStereo,
        )
        plan.resampleToHz shouldBe 44_100
    }

    @Test
    fun `null input channels forces ChannelMixing in the plan`() {
        val plan = planAudioProcessors(
            inputSampleRate = 44_100,
            inputChannels = null,
            config = config44kStereo,
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 2
    }

    @Test
    fun `null input sample rate and channels forces both`() {
        val plan = planAudioProcessors(
            inputSampleRate = null,
            inputChannels = null,
            config = config22kMono,
        )
        plan.channelMixing.shouldNotBeNull().outputChannelCount shouldBe 1
        plan.resampleToHz shouldBe 22_050
    }

    @Test
    fun `mono-to-mono same rate short-circuits to empty plan`() {
        val plan = planAudioProcessors(
            inputSampleRate = 22_050,
            inputChannels = 1,
            config = config22kMono,
        )
        plan.isEmpty shouldBe true
    }

    @Test
    fun `mono input at different rate plans only Sonic when target is mono`() {
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
