/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canonical 7.1 PCM fixture and ITU-R BS.775-3 reference matrices used by [Bs775DownmixMatrixTest].
 *
 * The fixture is the test's "committed PCM": a deterministic, parameter-pinned generator
 * (mirroring the existing `WavGenerator` pattern for internal audio fixtures) producing one
 * distinct sine wave per 7.1 channel. Bytes are derived from the constants below — anyone
 * can regenerate identical samples.
 *
 * 7.1 channel order (ISO/IEC 23001-8 Mpeg7_1_C):
 *   0=FL, 1=FR, 2=FC, 3=LFE, 4=BL, 5=BR, 6=SL, 7=SR
 *
 * Reference matrices encode the strict ITU-R BS.775-3 downmix formulas (LFE excluded;
 * surrounds folded with the 1/√2 attenuation prescribed by §3 / §3.5). Any divergence
 * between [surroundDownmixMatrix] and these references is either within the ±0.01 tolerance
 * of [Bs775DownmixMatrixTest] or is documented in [docs/audio-downmix.md] and pinned via
 * [Bs775DownmixMatrixTest.IntentionalDivergences].
 */
internal object Bs775ReferenceFixture {

    const val SAMPLE_RATE_HZ: Int = 48_000

    /**
     * Short canonical signal — long enough to span several full periods of every channel's tone
     * yet small enough that the test stays sub-millisecond. 100 ms × 48 kHz = 4 800 samples.
     */
    const val SAMPLE_COUNT: Int = 4_800

    const val CHANNELS_71: Int = 8

    /**
     * Base tone frequency. Each channel `ch` gets `BASE_TONE_HZ * (ch + 1)` so every channel
     * has a unique, easily distinguishable spectrum:
     *   FL=440, FR=880, FC=1320, LFE=1760, BL=2200, BR=2640, SL=3080, SR=3520 Hz.
     * Matches [WavGenerator]'s convention so the fixture is consistent with the rest of the
     * test suite.
     */
    const val BASE_TONE_HZ: Double = 440.0

    /**
     * Generate the canonical 7.1 PCM signal as `Array<FloatArray>` keyed by channel index.
     * Each inner array contains [SAMPLE_COUNT] samples in the range `[-1.0, 1.0]`.
     */
    fun generateCanonical71Pcm(): Array<FloatArray> = Array(CHANNELS_71) { ch ->
        val frequency = BASE_TONE_HZ * (ch + 1)
        FloatArray(SAMPLE_COUNT) { i ->
            sin(2.0 * PI * frequency * i / SAMPLE_RATE_HZ).toFloat()
        }
    }

    /**
     * Apply a row-major `(outputChannels × inputChannels)` mixing matrix to a channel-keyed
     * PCM signal, returning the resulting per-output-channel sample arrays.
     *
     * `outputs[out][t] = sum over `in` of matrix[out * inputChannels + in] * inputs[in][t]`.
     */
    fun applyMatrix(
        inputs: Array<FloatArray>,
        matrix: FloatArray,
        outputChannels: Int,
    ): Array<FloatArray> {
        val inputChannels = inputs.size
        val sampleCount = inputs[0].size
        return Array(outputChannels) { out ->
            FloatArray(sampleCount) { t ->
                var acc = 0.0
                for (input in 0 until inputChannels) {
                    acc += matrix[out * inputChannels + input] * inputs[input][t]
                }
                acc.toFloat()
            }
        }
    }

    /**
     * The exact 1/√2 surround/center attenuation prescribed by ITU-R BS.775. Distinct from the
     * 4-decimal `0.7071f` literal used by [surroundDownmixMatrix] — the spread between them
     * (≈ 6.8e-6) is well within the ±0.01 tolerance the test enforces.
     */
    val SQRT_HALF: Float = (1.0 / sqrt(2.0)).toFloat()

    /**
     * ITU-R BS.775-3 7.1 → stereo reference (chained via 5.1):
     *   L = FL + (1/√2)·FC + 0.5·BL + 0.5·SL
     *   R = FR + (1/√2)·FC + 0.5·BR + 0.5·SR
     * LFE excluded.
     *
     * Row-major: row 0 (L) at indices 0..7, row 1 (R) at indices 8..15.
     */
    val REFERENCE_8_TO_2: FloatArray = floatArrayOf(
        // L row: FL,  FR,    FC,         LFE,  BL,    BR,   SL,    SR
        1.0f, 0.0f, SQRT_HALF, 0.0f, 0.5f, 0.0f, 0.5f, 0.0f,
        // R row: FL,  FR,    FC,         LFE,  BL,    BR,   SL,    SR
        0.0f, 1.0f, SQRT_HALF, 0.0f, 0.0f, 0.5f, 0.0f, 0.5f,
    )

    /**
     * ITU-R BS.775-3 7.1 → 5.1 reference (§3.5):
     *   FL_5.1 = FL_7.1, FR_5.1 = FR_7.1, FC_5.1 = FC_7.1, LFE_5.1 = LFE_7.1
     *   Ls_5.1 = (1/√2)·BL_7.1 + (1/√2)·SL_7.1
     *   Rs_5.1 = (1/√2)·BR_7.1 + (1/√2)·SR_7.1
     *
     * Row-major over 6 outputs × 8 inputs.
     */
    val REFERENCE_8_TO_6: FloatArray = floatArrayOf(
        // FL = FL
        1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        // FR = FR
        0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        // FC = FC
        0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        // LFE = LFE
        0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        // Ls = (1/√2)·BL + (1/√2)·SL
        0.0f, 0.0f, 0.0f, 0.0f, SQRT_HALF, 0.0f, SQRT_HALF, 0.0f,
        // Rs = (1/√2)·BR + (1/√2)·SR
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, SQRT_HALF, 0.0f, SQRT_HALF,
    )
}
