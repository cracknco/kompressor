/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.io.CompressionProgress.Phase.COMPRESSING
import co.crackn.kompressor.io.CompressionProgress.Phase.FINALIZING_OUTPUT
import co.crackn.kompressor.io.CompressionProgress.Phase.MATERIALIZING_INPUT
import co.crackn.kompressor.testutil.assertProgressionMonotone
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * Unit tests for the [assertProgressionMonotone] utility itself. Covers every rejection branch
 * so the assertion is trusted by the end-to-end progression tests that depend on it.
 */
class ProgressionInvariantsTest {

    @Test
    fun acceptsEmptySequence() {
        // Empty is a degenerate "no events" case — not a violation.
        assertProgressionMonotone(emptyList())
    }

    @Test
    fun acceptsCanonicalFullSequence() {
        assertProgressionMonotone(
            listOf(
                CompressionProgress(MATERIALIZING_INPUT, 0f),
                CompressionProgress(MATERIALIZING_INPUT, 0.5f),
                CompressionProgress(MATERIALIZING_INPUT, 1f),
                CompressionProgress(COMPRESSING, 0f),
                CompressionProgress(COMPRESSING, 0.25f),
                CompressionProgress(COMPRESSING, 0.5f),
                CompressionProgress(COMPRESSING, 0.75f),
                CompressionProgress(FINALIZING_OUTPUT, 0.5f),
                CompressionProgress(FINALIZING_OUTPUT, 1f),
            ),
        )
    }

    @Test
    fun acceptsSequenceThatSkipsMaterializingInput() {
        // FilePath / native builder inputs skip MATERIALIZING_INPUT — this is expected.
        assertProgressionMonotone(
            listOf(
                CompressionProgress(COMPRESSING, 0f),
                CompressionProgress(COMPRESSING, 0.5f),
                CompressionProgress(FINALIZING_OUTPUT, 1f),
            ),
        )
    }

    @Test
    fun acceptsRepeatedSameFractionWithinPhase() {
        // Coalesced ticks (e.g. materializer emits the same fraction twice when sizeHint
        // undercounts) are allowed — monotonicity is `<=`, not `<`.
        assertProgressionMonotone(
            listOf(
                CompressionProgress(COMPRESSING, 0.5f),
                CompressionProgress(COMPRESSING, 0.5f),
                CompressionProgress(COMPRESSING, 0.5f),
            ),
        )
    }

    @Test
    fun rejectsFractionDecreaseWithinPhase() {
        val ex = shouldThrow<IllegalArgumentException> {
            assertProgressionMonotone(
                listOf(
                    CompressionProgress(COMPRESSING, 0.6f),
                    CompressionProgress(COMPRESSING, 0.4f),
                ),
            )
        }
        ex.message!! shouldContain "fraction decreased within phase"
        ex.message!! shouldContain "COMPRESSING"
    }

    @Test
    fun rejectsPhaseRegression() {
        val ex = shouldThrow<IllegalStateException> {
            assertProgressionMonotone(
                listOf(
                    CompressionProgress(COMPRESSING, 0.5f),
                    CompressionProgress(MATERIALIZING_INPUT, 0f),
                ),
            )
        }
        ex.message!! shouldContain "phase regression"
    }

    @Test
    fun rejectsFinalizingBackToCompressing() {
        val ex = shouldThrow<IllegalStateException> {
            assertProgressionMonotone(
                listOf(
                    CompressionProgress(COMPRESSING, 1f),
                    CompressionProgress(FINALIZING_OUTPUT, 0.5f),
                    CompressionProgress(COMPRESSING, 0.75f),
                ),
            )
        }
        ex.message!! shouldContain "phase regression"
        ex.message!! shouldContain "COMPRESSING"
    }

    @Test
    fun rejectsFractionAboveOneViaHandCrafted() {
        // The CompressionProgress constructor rejects fraction > 1 itself, so this is
        // primarily a documentation test — construction fails before assertion runs.
        shouldThrow<IllegalArgumentException> {
            CompressionProgress(COMPRESSING, 1.01f)
        }
    }

    @Test
    fun rejectsFractionBelowZeroViaHandCrafted() {
        // Same as above — constructor invariant beats the helper.
        shouldThrow<IllegalArgumentException> {
            CompressionProgress(COMPRESSING, -0.01f)
        }
    }

    @Test
    fun acceptsPhaseAdvanceResetsWithinPhaseTracker() {
        // New phase can start at 0f even though previous phase ended at 1f — the within-phase
        // monotonicity tracker must reset, not carry over from the prior phase.
        assertProgressionMonotone(
            listOf(
                CompressionProgress(MATERIALIZING_INPUT, 1f),
                CompressionProgress(COMPRESSING, 0f),
                CompressionProgress(COMPRESSING, 1f),
                CompressionProgress(FINALIZING_OUTPUT, 0f),
                CompressionProgress(FINALIZING_OUTPUT, 1f),
            ),
        )
    }
}
