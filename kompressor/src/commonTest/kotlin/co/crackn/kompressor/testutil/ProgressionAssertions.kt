/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import co.crackn.kompressor.io.CompressionProgress

/**
 * Assert that a recorded [CompressionProgress] sequence obeys the three multi-phase invariants
 * the Kompressor API promises to its consumers (CRA-96):
 *
 *  1. **Fraction in bounds** — every event's `fraction` is in `[0.0, 1.0]` and non-NaN. The
 *     [CompressionProgress] constructor already enforces this at emission time, so a failure
 *     here signals a test-fixture bug (hand-crafted sequences only). Still checked up-front so
 *     the error message is clear.
 *
 *  2. **Monotone within a phase** — fractions inside the same phase never decrease
 *     (`<=` comparison allows repeats — real pipelines coalesce same-bucket ticks). Bouncing
 *     backwards would produce a jumpy UI bar.
 *
 *  3. **Phase order respects the canonical sequence** —
 *     `MATERIALIZING_INPUT → COMPRESSING → FINALIZING_OUTPUT`. Phases can be skipped (a
 *     [co.crackn.kompressor.io.MediaSource.Local.FilePath] input skips `MATERIALIZING_INPUT`);
 *     only **regression** to an earlier phase is rejected. The canonical order is the ordinal
 *     order of [CompressionProgress.Phase.entries].
 *
 * The consumer-facing progression guarantee is:
 *
 * > "Your progress bar only ever advances — never jumps backwards, never revisits a completed
 * > phase, and ends at `FINALIZING_OUTPUT(1.0)`."
 *
 * Used by ProgressionInvariantsTest (unit-test the assertion itself) and ProgressionE2ETest
 * (platform-side integration test that records emissions from a real compressor run).
 *
 * @throws AssertionError describing the first violation encountered (index, phase, fraction).
 */
internal fun assertProgressionMonotone(events: List<CompressionProgress>) {
    if (events.isEmpty()) return

    var prevPhaseOrdinal = -1
    var prevFractionInPhase = Float.NEGATIVE_INFINITY
    var currentPhaseOrdinal = -1

    events.forEachIndexed { index, event ->
        // (1) Fraction bounds — defence-in-depth against hand-crafted test sequences that
        // bypass the `CompressionProgress` `init` block (e.g. via reflection). Real-runtime
        // events never reach here failing this check.
        require(!event.fraction.isNaN() && event.fraction in 0f..1f) {
            "Progression[$index]: fraction out of [0,1] or NaN — got ${event.fraction} " +
                "in phase ${event.phase}"
        }

        val phaseOrdinal = event.phase.ordinal
        when {
            // (3) Phase regression — the current event's phase ordinal must not be earlier
            // than any previously observed phase. Phase skipping is allowed (FilePath input
            // skips MATERIALIZING_INPUT); backwards-hopping is not.
            phaseOrdinal < prevPhaseOrdinal -> error(
                "Progression[$index]: phase regression — current phase ${event.phase} " +
                    "(ordinal $phaseOrdinal) precedes previous max phase ordinal " +
                    "$prevPhaseOrdinal. Canonical order: " +
                    "${CompressionProgress.Phase.entries}",
            )
            // Phase advanced — reset the within-phase fraction tracker so the new phase can
            // legitimately start from 0.
            phaseOrdinal > currentPhaseOrdinal -> {
                currentPhaseOrdinal = phaseOrdinal
                prevFractionInPhase = event.fraction
            }
            // Same phase — (2) within-phase monotonicity.
            else -> {
                require(event.fraction >= prevFractionInPhase) {
                    "Progression[$index]: fraction decreased within phase ${event.phase} — " +
                        "went from $prevFractionInPhase to ${event.fraction}"
                }
                prevFractionInPhase = event.fraction
            }
        }
        prevPhaseOrdinal = maxOf(prevPhaseOrdinal, phaseOrdinal)
    }
}
