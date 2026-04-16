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
 *  - The per-sample stereo output of the canonical 7.1 fixture, after subtracting the
 *    contribution of the documented divergences, must match the BS.775-3 reference output
 *    within [SAMPLE_TOLERANCE]. This is the end-to-end version of the per-coefficient check:
 *    the matrix multiply itself is exercised, not just the coefficient values.
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
        // End-to-end: applying the impl matrix to the canonical fixture must yield the same
        // stereo output as applying the BS.775-3 reference matrix, *plus* the linear contribution
        // of the documented intentional divergences. Any matrix-multiply bug or undocumented
        // coefficient drift surfaces here as a per-sample tolerance failure.
        val inputs = Bs775ReferenceFixture.generateCanonical71Pcm()

        val implMatrix = surroundDownmixMatrix(CHANNELS_71, 2).shouldNotBeNull()
        val implOutput = Bs775ReferenceFixture.applyMatrix(inputs, implMatrix, outputChannels = 2)
        val refOutput = Bs775ReferenceFixture.applyMatrix(inputs, REFERENCE_8_TO_2, outputChannels = 2)

        for (out in 0 until 2) {
            val divergencesForRow = INTENTIONAL_DIVERGENCES_8_TO_2.filter { it.outputChannel == out }
            for (t in 0 until SAMPLE_COUNT) {
                var expectedDiff = 0.0
                for (d in divergencesForRow) {
                    val refValue = REFERENCE_8_TO_2[out * CHANNELS_71 + d.inputChannel]
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

        const val LFE_INDEX: Int = 3

        // 7.1 → stereo divergences vs BS.775-3 chained reference.
        // Surround coefficients (BL/SL = 0.7071) match the BS.775-3 chained reference within
        // tolerance — see docs/audio-downmix.md for the derivation note (single-step vs chained).
        val INTENTIONAL_DIVERGENCES_8_TO_2: List<IntentionalDivergence> = listOf(
            IntentionalDivergence(
                outputChannel = 0,
                inputChannel = LFE_INDEX,
                implValue = 0.5f,
                rationale = "L picks up LFE at 0.5 (≈ -6 dB) for low-end preservation on stereo " +
                    "headphones (BS.775 strict ref = 0.0). Matches Dolby Pro Logic II convention.",
            ),
            IntentionalDivergence(
                outputChannel = 1,
                inputChannel = LFE_INDEX,
                implValue = 0.5f,
                rationale = "R picks up LFE at 0.5 (≈ -6 dB), symmetric to L.",
            ),
            // 7.1 stereo surround coefficients (BL/SL/BR/SR = 0.7071) treat each 7.1 surround
            // as an independent BS.775 surround channel — matches the §3 5.1 stereo formula
            // applied per-surround. Ref above also encodes 0.5 (chained 7.1 → 5.1 → 2.0); the
            // ~0.21 diff exceeds tolerance and is the only matrix-shape divergence vs ref.
            IntentionalDivergence(
                outputChannel = 0,
                inputChannel = 4,
                implValue = 0.7071f,
                rationale = "BL into L at 1/√2 (single-step BS.775 §3, treating BL as a 5.1 " +
                    "surround) vs chained ref 0.5. Documented in docs/audio-downmix.md.",
            ),
            IntentionalDivergence(
                outputChannel = 0,
                inputChannel = 6,
                implValue = 0.7071f,
                rationale = "SL into L at 1/√2 (single-step BS.775 §3) vs chained ref 0.5.",
            ),
            IntentionalDivergence(
                outputChannel = 1,
                inputChannel = 5,
                implValue = 0.7071f,
                rationale = "BR into R at 1/√2 (single-step BS.775 §3) vs chained ref 0.5.",
            ),
            IntentionalDivergence(
                outputChannel = 1,
                inputChannel = 7,
                implValue = 0.7071f,
                rationale = "SR into R at 1/√2 (single-step BS.775 §3) vs chained ref 0.5.",
            ),
        )

        // 7.1 → 5.1: BL/BR rows preserve back-channel content at unit gain (1.0) instead of
        // the BS.775 §3.5 1/√2 attenuation. Side-surround folding into Ls/Rs at 1/√2 matches
        // the spec.
        val INTENTIONAL_DIVERGENCES_8_TO_6: List<IntentionalDivergence> = listOf(
            IntentionalDivergence(
                outputChannel = 4,
                inputChannel = 4,
                implValue = 1.0f,
                rationale = "Ls_5.1 ← 1.0·BL (back-left passes through full-gain) vs BS.775 §3.5 " +
                    "1/√2. Avoids loudness loss when 7.1 source has no SL content.",
            ),
            IntentionalDivergence(
                outputChannel = 5,
                inputChannel = 5,
                implValue = 1.0f,
                rationale = "Rs_5.1 ← 1.0·BR symmetric to Ls/BL.",
            ),
        )
    }
}
