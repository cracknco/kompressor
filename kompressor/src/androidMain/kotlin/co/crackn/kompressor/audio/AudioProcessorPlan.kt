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
                    // Register matrices ONLY for input counts that we can actually mix down to
                    // `spec.outputChannelCount`. Earlier revisions registered the full envelope
                    // 1..MAX (including impossible combos like 7→2, 1→6) and relied on Media3's
                    // own `ChannelMixingMatrix.createForConstantPower` to error on demand —
                    // problem is `createForConstantPower` throws `UnsupportedOperationException`
                    // *at construction time*, so even valid `compress()` calls crashed because
                    // the loop instantiated an impossible matrix before Media3 ever inspected
                    // the real input format. CodeRabbit #2 / failing FTL device tests.
                    for (inputChannels in supportedInputCountsForOutput(spec.outputChannelCount)) {
                        putChannelMixingMatrix(
                            buildChannelMixingMatrix(inputChannels, spec.outputChannelCount),
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
 * Builds the [ChannelMixingMatrix] for one (input, output) channel-count combination. Falls
 * back through three strategies in order:
 *
 * 1. Identity matrix when input == output (passthrough).
 * 2. Media3's [ChannelMixingMatrix.createForConstantPower] when both counts are within its
 *    supported defaults (input ∈ 1..6, output ∈ {1, 2}).
 * 3. Hand-rolled ITU-R BS.775 matrix from [surroundDownmixMatrix] for 7.1 → mono / stereo /
 *    5.1.
 *
 * Throws [UnsupportedOperationException] for genuinely unsupported combinations (e.g. upmix or
 * 7.1 → 4.0); callers are expected to have already rejected these via the
 * [AudioCompressionError.UnsupportedConfiguration] path before instantiating the chain.
 */
@Suppress("ReturnCount")
internal fun buildChannelMixingMatrix(
    inputChannels: Int,
    outputChannels: Int,
): ChannelMixingMatrix {
    if (inputChannels == outputChannels) {
        return ChannelMixingMatrix(inputChannels, outputChannels, identityCoefficients(inputChannels))
    }
    surroundDownmixMatrix(inputChannels, outputChannels)?.let { coefficients ->
        return ChannelMixingMatrix(inputChannels, outputChannels, coefficients)
    }
    return ChannelMixingMatrix.createForConstantPower(inputChannels, outputChannels)
}

private fun identityCoefficients(channelCount: Int): FloatArray {
    val matrix = FloatArray(channelCount * channelCount)
    for (i in 0 until channelCount) matrix[i * channelCount + i] = 1f
    return matrix
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

// We support 1..6 (mono..5.1) and 8 (7.1) input channel counts. 7-channel inputs are NOT
// supported: Media3's `createForConstantPower` doesn't ship 7→{1,2} coefficients and our
// `surroundDownmixMatrix` only covers 8-channel sources.
private val SUPPORTED_INPUT_CHANNELS = setOf(1, 2, 3, 4, 5, 6, 8)

/**
 * Map a target output-channel count to the set of input-channel counts the chain can downmix
 * (or identity-passthrough) into it. Used both as a pre-flight gate (the public
 * [checkChannelMixSupported]) and to drive [AudioProcessorPlan.toProcessors]'s matrix
 * pre-registration loop so it never instantiates an impossible matrix.
 *
 * Coverage matrix:
 * - output ∈ {1, 2}: Media3 `createForConstantPower` handles inputs 1..6; our 8→{1,2}
 *   surround matrices extend that to 7.1 sources.
 * - output = 6 (5.1): identity for 6→6, our 8→6 surround matrix for 7.1 sources.
 * - any other output count: identity-passthrough only (input must equal output).
 */
internal fun supportedInputCountsForOutput(outputChannels: Int): Set<Int> = when (outputChannels) {
    1, 2 -> SUPPORTED_INPUT_CHANNELS
    6 -> setOf(6, 8)
    else -> setOf(outputChannels)
}

/**
 * Reject input files whose channel count is outside the supported envelope. The envelope is
 * [SUPPORTED_INPUT_CHANNELS] = {1..6, 8}; 7-channel and 9+-channel inputs surface a typed
 * [AudioCompressionError.UnsupportedConfiguration] upfront so callers can `when`-branch
 * instead of seeing an opaque Media3 mid-pipeline crash.
 *
 * `null` channel count (probe didn't report it) is treated as acceptable — the real pipeline
 * will surface its own error if the probe failure reflects a genuinely unreadable input.
 *
 * Pure function with no platform dependencies, exposed `internal` so the validation rule is
 * host-testable without spinning up a multichannel device fixture.
 */
internal fun checkSupportedInputChannelCount(inputChannels: Int?) {
    if (inputChannels != null && inputChannels !in SUPPORTED_INPUT_CHANNELS) {
        throw AudioCompressionError.UnsupportedConfiguration(
            "Input has $inputChannels channels; supported counts are 1..6 and 8 (7.1 surround)",
        )
    }
}

/**
 * Reject (input, output) channel-count pairs the mixer can't satisfy — primarily upmix attempts
 * (e.g. stereo → 5.1) and 7-channel inputs into outputs that have no matrix for them. Throws a
 * typed [AudioCompressionError.UnsupportedConfiguration] before [AudioProcessorPlan.toProcessors]
 * runs, so callers see the contract-promised error class rather than a Media3 mid-pipeline
 * `UnsupportedOperationException`.
 *
 * `null` input is treated as acceptable for the same reason as [checkSupportedInputChannelCount].
 */
internal fun checkChannelMixSupported(inputChannels: Int?, outputChannels: Int) {
    if (inputChannels != null && inputChannels !in supportedInputCountsForOutput(outputChannels)) {
        throw AudioCompressionError.UnsupportedConfiguration(
            "Cannot mix $inputChannels-channel input into $outputChannels-channel output; " +
                "supported inputs for $outputChannels-channel output are " +
                supportedInputCountsForOutput(outputChannels).sorted(),
        )
    }
}

/**
 * Hand-rolled ITU-R BS.775-based downmix matrices for 7.1 (8-channel) inputs that Media3
 * 1.10's `createForConstantPower` does not ship defaults for.
 *
 * 7.1 channel order (ISO/IEC 23001-8 Mpeg7_1_C):
 *   0=FL, 1=FR, 2=FC, 3=LFE, 4=BL, 5=BR, 6=SL, 7=SR
 *
 * Matrices are returned in row-major order: `coefficients[outputChannel * 8 + inputChannel]`.
 * Coefficients use the constant-power 0.7071 (≈ 1/√2) for diagonal mixes, the BS.775 0.5 for
 * LFE / surround folding into stereo, and 1.0 for pass-through diagonals.
 *
 * Returns `null` for combinations not in this table — callers fall back to Media3's defaults
 * via [buildChannelMixingMatrix].
 */
@Suppress("MagicNumber")
internal fun surroundDownmixMatrix(inputChannels: Int, outputChannels: Int): FloatArray? {
    if (inputChannels != 8) return null
    return when (outputChannels) {
        1 -> floatArrayOf(
            // mono = (FL + FR)·0.7071 + FC + (BL + BR + SL + SR)·0.5 + LFE·0.7071
            0.7071f, 0.7071f, 1.0f, 0.7071f, 0.5f, 0.5f, 0.5f, 0.5f,
        )
        2 -> floatArrayOf(
            // L = FL + 0.7071·FC + 0.5·LFE + 0.7071·BL + 0.7071·SL
            1.0f, 0.0f, 0.7071f, 0.5f, 0.7071f, 0.0f, 0.7071f, 0.0f,
            // R = FR + 0.7071·FC + 0.5·LFE + 0.7071·BR + 0.7071·SR
            0.0f, 1.0f, 0.7071f, 0.5f, 0.0f, 0.7071f, 0.0f, 0.7071f,
        )
        6 -> floatArrayOf(
            // 7.1 → 5.1: fold side surrounds into back surrounds (BS.775 §3.5)
            // FL = FL
            1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            // FR = FR
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            // FC = FC
            0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            // LFE = LFE
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            // BL = BL + 0.7071·SL
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.7071f, 0.0f,
            // BR = BR + 0.7071·SR
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.7071f,
        )
        else -> null
    }
}
