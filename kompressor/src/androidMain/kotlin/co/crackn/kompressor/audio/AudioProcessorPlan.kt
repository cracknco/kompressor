package co.crackn.kompressor.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor

/**
 * A declarative description of the Media3 audio-processor chain needed for one export.
 *
 * This split (plan → processors) exists so that the *decision* of which processors to wire up
 * can be exercised by host-side unit tests. Instantiating the real [AudioProcessor]s is
 * deferred to [toProcessors] because their constructors touch Android-only classes
 * (`android.util.SparseArray`) that are not available on the host JVM.
 *
 * That is what replaces the coverage previously provided by `PcmProcessorTest` /
 * `PcmRingBufferTest`: the PCM plumbing itself is Media3's responsibility now, but the
 * configuration of that plumbing is the only vector this library can introduce bugs into.
 */
internal data class AudioProcessorPlan(
    val channelMixing: ChannelMixingSpec?,
    val resampleToHz: Int?,
) {
    internal data class ChannelMixingSpec(val outputChannelCount: Int)

    /** True when no processors are needed — Media3 can passthrough the input stream unchanged. */
    val isEmpty: Boolean get() = channelMixing == null && resampleToHz == null

    /**
     * Materialise the plan into concrete Media3 [AudioProcessor] instances in the canonical
     * order `[channelMixing, sonic]` — mix channels first so Sonic resamples fewer bytes.
     */
    fun toProcessors(): List<AudioProcessor> = buildList {
        channelMixing?.let { spec ->
            add(
                ChannelMixingAudioProcessor().apply {
                    // Register matrices for every input-channel count for which Media3 ships a
                    // default constant-gain matrix. In Media3 1.10 that is limited to 1→{1,2}
                    // and 2→{1,2} — `ChannelMixingMatrix.createForConstantGain` throws
                    // `UnsupportedOperationException` for anything ≥ 3 input channels.
                    //
                    // Multichannel inputs (5.1 / 7.1) will surface a Media3 configure-time
                    // error with a clear message rather than a mysterious runtime crash.
                    // The `ChannelMixingAudioProcessor` picks the right matrix from the actual
                    // input AudioFormat at configure time, so pre-registering both supported
                    // input counts is safe regardless of which the decoder emits.
                    for (inputChannels in 1..MAX_SUPPORTED_INPUT_CHANNELS) {
                        putChannelMixingMatrix(
                            ChannelMixingMatrix.createForConstantGain(
                                inputChannels,
                                spec.outputChannelCount,
                            ),
                        )
                    }
                },
            )
        }
        resampleToHz?.let { rateHz ->
            add(SonicAudioProcessor().apply { setOutputSampleRateHz(rateHz) })
        }
    }
}

/**
 * Builds the [AudioProcessorPlan] needed to transform an input stream's sample rate and channel
 * count to the values requested in [config].
 *
 * Pure — no side effects, no [android.content.Context] dependency — so every decision can be
 * exercised by host-side unit tests without a device.
 *
 * Contract:
 * - When [inputSampleRate] and [inputChannels] exactly match the target config, an empty plan
 *   is returned so Media3 can activate its bitstream-passthrough fast path.
 * - When either dimension is `null` (input format not known at build time) or differs, the
 *   corresponding processor is included.
 */
internal fun planAudioProcessors(
    inputSampleRate: Int?,
    inputChannels: Int?,
    config: AudioCompressionConfig,
): AudioProcessorPlan {
    val matchesTarget =
        inputSampleRate == config.sampleRate && inputChannels == config.channels.count
    if (matchesTarget) return AudioProcessorPlan(channelMixing = null, resampleToHz = null)

    val channelsMismatch = inputChannels == null || inputChannels != config.channels.count
    val sampleRateMismatch = inputSampleRate == null || inputSampleRate != config.sampleRate

    return AudioProcessorPlan(
        channelMixing = if (channelsMismatch) {
            AudioProcessorPlan.ChannelMixingSpec(outputChannelCount = config.channels.count)
        } else {
            null
        },
        resampleToHz = if (sampleRateMismatch) config.sampleRate else null,
    )
}

// Media3 1.10's `ChannelMixingMatrix.createForConstantGain` only ships default coefficients for
// mono/stereo inputs. Higher input channel counts throw `UnsupportedOperationException`.
private const val MAX_SUPPORTED_INPUT_CHANNELS = 2

/**
 * Reject input files whose channel count exceeds what Media3's built-in mixer can handle.
 *
 * Media3 1.10 ships `ChannelMixingMatrix.createForConstantGain` default coefficients only for
 * 1..2 input channels — 5.1 / 7.1 sources throw [UnsupportedOperationException] mid-configure
 * with no actionable context. Rejecting upfront with a typed
 * [AudioCompressionError.UnsupportedConfiguration] lets callers `when`-branch and surface a
 * useful error (e.g. "please down-mix to stereo first").
 *
 * `null` channel count (probe didn't report it) is treated as acceptable — the real pipeline
 * will surface its own error if the probe failure reflects a genuinely unreadable input.
 *
 * Pure function with no platform dependencies, exposed `internal` so the validation rule is
 * host-testable without spinning up a 5.1 device fixture.
 */
internal fun checkSupportedInputChannelCount(inputChannels: Int?) {
    if (inputChannels != null && inputChannels > MAX_SUPPORTED_INPUT_CHANNELS) {
        throw AudioCompressionError.UnsupportedConfiguration(
            "Input has $inputChannels channels; only mono and stereo sources are supported",
        )
    }
}
