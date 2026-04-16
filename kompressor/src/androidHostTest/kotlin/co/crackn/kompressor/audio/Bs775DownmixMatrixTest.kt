/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import co.crackn.kompressor.audio.Bs775ReferenceFixture.CHANNELS_71
import co.crackn.kompressor.audio.Bs775ReferenceFixture.REFERENCE_8_TO_2
import co.crackn.kompressor.audio.Bs775ReferenceFixture.REFERENCE_8_TO_6
import co.crackn.kompressor.audio.Bs775ReferenceFixture.SAMPLE_COUNT
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * Pins [surroundDownmixMatrix] against the strict ITU-R BS.775-3 reference matrices defined
 * in [Bs775ReferenceFixture]. Per CRA-13:
 *
 *  - Each (input, output) coefficient must be within ±[COEFFICIENT_TOLERANCE] of the BS.775-3
 *    reference *unless* the position appears in [INTENTIONAL_DIVERGENCES_*] — in which case the
 *    impl must match the pinned divergence value exactly. Any drift on either side surfaces a
 *    typed assertion failure with the position and rationale.
 *  - The per-sample output of the canonical 7.1 fixture, after subtracting the linear
 *    contribution of the documented divergences, must match the BS.775-3 reference output
 *    within [SAMPLE_TOLERANCE]. Since both impl and reference share the same
 *    [Bs775ReferenceFixture.applyMatrix] helper, a bug in that helper would cancel out — what
 *    this check *does* catch is **completeness of the divergence inventory**: any coefficient
 *    that drifts from the reference without being listed in `INTENTIONAL_DIVERGENCES_*` shows
 *    up as a per-sample residual outside the tolerance envelope.
 *
 * Sister test [SurroundChannelMixingTest] pins the impl coefficients exactly without reference
 * comparison; this test is the spec-conformance complement and the doc anchor.
 *
 * See [docs/audio-downmix.md] for the full matrix tables and the rationale behind every
 * intentional divergence.
 */
class Bs775DownmixMatrixTest {

    /** Position in a row-major matrix where our impl deliberately diverges from BS.775-3. */
    private data class IntentionalDivergence(
        val outputChannel: Int,
        val inputChannel: Int,
        val implValue: Float,
        val rationale: String,
    )

    @Test
    fun stereoCoefficients_matchBs775ReferenceWithinToleranceOrPinnedDivergence() {
        val impl = surroundDownmixMatrix(CHANNELS_71, 2).shouldNotBeNull()
        assertCoefficientsMatchReference(
            impl = impl,
            reference = REFERENCE_8_TO_2,
            outputChannels = 2,
            divergences = INTENTIONAL_DIVERGENCES_8_TO_2,
        )
    }

    @Test
    fun fivePointOneCoefficients_matchBs775ReferenceWithinToleranceOrPinnedDivergence() {
        val impl = surroundDownmixMatrix(CHANNELS_71, 6).shouldNotBeNull()
        assertCoefficientsMatchReference(
            impl = impl,
            reference = REFERENCE_8_TO_6,
            outputChannels = 6,
            divergences = INTENTIONAL_DIVERGENCES_8_TO_6,
        )
    }

    @Test
    fun stereoOutput_matchesBs775ReferencePlusDocumentedDivergences() {
        val implMatrix = surroundDownmixMatrix(CHANNELS_71, 2).shouldNotBeNull()
        assertPerSampleOutputMatchesReferencePlusDivergences(
            implMatrix = implMatrix,
            referenceMatrix = REFERENCE_8_TO_2,
            outputChannels = 2,
            divergences = INTENTIONAL_DIVERGENCES_8_TO_2,
        )
    }

    @Test
    fun fivePointOneOutput_matchesBs775ReferencePlusDocumentedDivergences() {
        val implMatrix = surroundDownmixMatrix(CHANNELS_71, 6).shouldNotBeNull()
        assertPerSampleOutputMatchesReferencePlusDivergences(
            implMatrix = implMatrix,
            referenceMatrix = REFERENCE_8_TO_6,
            outputChannels = 6,
            divergences = INTENTIONAL_DIVERGENCES_8_TO_6,
        )
    }

    /**
     * Applies [implMatrix] and [referenceMatrix] to the canonical 7.1 fixture and asserts the
     * per-sample diff matches the linear contribution of [divergences]. See the class KDoc for
     * what this actually catches (divergence-inventory completeness, not `applyMatrix` bugs).
     */
    private fun assertPerSampleOutputMatchesReferencePlusDivergences(
        implMatrix: FloatArray,
        referenceMatrix: FloatArray,
        outputChannels: Int,
        divergences: List<IntentionalDivergence>,
    ) {
        val inputs = Bs775ReferenceFixture.generateCanonical71Pcm()
        val implOutput = Bs775ReferenceFixture.applyMatrix(inputs, implMatrix, outputChannels)
        val refOutput = Bs775ReferenceFixture.applyMatrix(inputs, referenceMatrix, outputChannels)

        for (out in 0 until outputChannels) {
            val divergencesForRow = divergences.filter { it.outputChannel == out }
            for (t in 0 until SAMPLE_COUNT) {
                var expectedDiff = 0.0
                for (d in divergencesForRow) {
                    val refValue = referenceMatrix[out * CHANNELS_71 + d.inputChannel]
                    expectedDiff += (d.implValue - refValue) * inputs[d.inputChannel][t]
                }
                val actualDiff = (implOutput[out][t] - refOutput[out][t]).toDouble()
                abs(actualDiff - expectedDiff).shouldBeLessThanOrEqual(SAMPLE_TOLERANCE)
            }
        }
    }

    private fun assertCoefficientsMatchReference(
        impl: FloatArray,
        reference: FloatArray,
        outputChannels: Int,
        divergences: List<IntentionalDivergence>,
    ) {
        impl.size shouldBe reference.size
        val divergenceByPosition = divergences.associateBy { it.outputChannel to it.inputChannel }
        for (out in 0 until outputChannels) {
            for (input in 0 until CHANNELS_71) {
                val index = out * CHANNELS_71 + input
                val implValue = impl[index]
                val referenceValue = reference[index]
                val pinned = divergenceByPosition[out to input]
                if (pinned != null) {
                    // Intentional divergence — impl value must match the pinned literal exactly.
                    // If the impl drifts (or the divergence is removed), this assertion fires
                    // before the tolerance check below.
                    implValue shouldBe pinned.implValue
                } else {
                    val diff = abs(implValue - referenceValue).toDouble()
                    diff.shouldBeLessThanOrEqual(COEFFICIENT_TOLERANCE)
                }
            }
        }
    }

    private companion object {
        // ITU-R BS.775-3 conformance tolerance per CRA-13 DoD.
        const val COEFFICIENT_TOLERANCE: Double = 0.01

        // Per-sample tolerance for the stereo output comparison. Two orders of magnitude
        // tighter than the coefficient tolerance because once the divergence contribution is
        // subtracted the only residual is the 0.7071f → 1/√2 floating-point spread on
        // non-divergent positions (≈ 6.8e-6 per coefficient).
        const val SAMPLE_TOLERANCE: Double = 1e-4

        // 7.1 input channel indices used by the divergence lists below (ISO/IEC 23001-8
        // Mpeg7_1_C order: 0=FL, 1=FR, 2=FC, 3=LFE, 4=BL, 5=BR, 6=SL, 7=SR). Only the
        // divergent channels are named here; see docs/audio-downmix.md for the full layout.
        const val LFE_INDEX: Int = 3
        const val BL_INDEX: Int = 4
        const val BR_INDEX: Int = 5
        const val SL_INDEX: Int = 6
        const val SR_INDEX: Int = 7

        // Stereo output channel indices.
        const val L_OUT: Int = 0
        const val R_OUT: Int = 1

        // 5.1 output channel indices (FL/FR/FC/LFE/Ls/Rs per Media3 CHANNEL_OUT_5POINT1).
        const val LS_OUT_5_1: Int = 4
        const val RS_OUT_5_1: Int = 5

        // 7.1 → stereo divergences vs BS.775-3 chained reference.
        // Surround coefficients (BL/SL = 0.7071) match the BS.775-3 chained reference within
        // tolerance — see docs/audio-downmix.md for the derivation note (single-step vs chained).
        val INTENTIONAL_DIVERGENCES_8_TO_2: List<IntentionalDivergence> = listOf(
            IntentionalDivergence(
                outputChannel = L_OUT,
                inputChannel = LFE_INDEX,
                implValue = 0.5f,
                rationale = "L picks up LFE at 0.5 (≈ -6 dB) for low-end preservation on stereo " +
                    "headphones (BS.775 strict ref = 0.0). Matches Dolby Pro Logic II convention.",
            ),
            IntentionalDivergence(
                outputChannel = R_OUT,
                inputChannel = LFE_INDEX,
                implValue = 0.5f,
                rationale = "R picks up LFE at 0.5 (≈ -6 dB), symmetric to L.",
            ),
            // 7.1 stereo surround coefficients (BL/SL/BR/SR = 0.7071) treat each 7.1 surround
            // as an independent BS.775 surround channel — matches the §3 5.1 stereo formula
            // applied per-surround. Ref above also encodes 0.5 (chained 7.1 → 5.1 → 2.0); the
            // ~0.21 diff exceeds tolerance and is the only matrix-shape divergence vs ref.
            IntentionalDivergence(
                outputChannel = L_OUT,
                inputChannel = BL_INDEX,
                implValue = 0.7071f,
                rationale = "BL into L at 1/√2 (single-step BS.775 §3, treating BL as a 5.1 " +
                    "surround) vs chained ref 0.5. Documented in docs/audio-downmix.md.",
            ),
            IntentionalDivergence(
                outputChannel = L_OUT,
                inputChannel = SL_INDEX,
                implValue = 0.7071f,
                rationale = "SL into L at 1/√2 (single-step BS.775 §3) vs chained ref 0.5.",
            ),
            IntentionalDivergence(
                outputChannel = R_OUT,
                inputChannel = BR_INDEX,
                implValue = 0.7071f,
                rationale = "BR into R at 1/√2 (single-step BS.775 §3) vs chained ref 0.5.",
            ),
            IntentionalDivergence(
                outputChannel = R_OUT,
                inputChannel = SR_INDEX,
                implValue = 0.7071f,
                rationale = "SR into R at 1/√2 (single-step BS.775 §3) vs chained ref 0.5.",
            ),
        )

        // 7.1 → 5.1: BL/BR rows preserve back-channel content at unit gain (1.0) instead of
        // the BS.775 §3.5 1/√2 attenuation. Side-surround folding into Ls/Rs at 1/√2 matches
        // the spec.
        val INTENTIONAL_DIVERGENCES_8_TO_6: List<IntentionalDivergence> = listOf(
            IntentionalDivergence(
                outputChannel = LS_OUT_5_1,
                inputChannel = BL_INDEX,
                implValue = 1.0f,
                rationale = "Ls_5.1 ← 1.0·BL (back-left passes through full-gain) vs BS.775 §3.5 " +
                    "1/√2. Avoids loudness loss when 7.1 source has no SL content.",
            ),
            IntentionalDivergence(
                outputChannel = RS_OUT_5_1,
                inputChannel = BR_INDEX,
                implValue = 1.0f,
                rationale = "Rs_5.1 ← 1.0·BR symmetric to Ls/BL.",
            ),
        )
    }
}
