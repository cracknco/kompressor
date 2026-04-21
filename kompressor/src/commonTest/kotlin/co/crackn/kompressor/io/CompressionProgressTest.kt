/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class CompressionProgressTest {

    @Test
    fun phaseEnumHasThreeValuesInCanonicalOrder() {
        CompressionProgress.Phase.entries.toList() shouldBe listOf(
            CompressionProgress.Phase.MATERIALIZING_INPUT,
            CompressionProgress.Phase.COMPRESSING,
            CompressionProgress.Phase.FINALIZING_OUTPUT,
        )
    }

    @Test
    fun dataClassEquality() {
        val a = CompressionProgress(CompressionProgress.Phase.COMPRESSING, 0.5f)
        val b = CompressionProgress(CompressionProgress.Phase.COMPRESSING, 0.5f)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun dataClassInequality() {
        val a = CompressionProgress(CompressionProgress.Phase.COMPRESSING, 0.5f)
        val b = CompressionProgress(CompressionProgress.Phase.COMPRESSING, 0.75f)
        val c = CompressionProgress(CompressionProgress.Phase.FINALIZING_OUTPUT, 0.5f)
        a shouldNotBe b
        a shouldNotBe c
    }

    @Test
    fun componentAccessorsAndCopy() {
        val p = CompressionProgress(CompressionProgress.Phase.MATERIALIZING_INPUT, 0.25f)
        p.phase shouldBe CompressionProgress.Phase.MATERIALIZING_INPUT
        p.fraction shouldBe 0.25f
        val copy = p.copy(fraction = 0.5f)
        copy.phase shouldBe CompressionProgress.Phase.MATERIALIZING_INPUT
        copy.fraction shouldBe 0.5f
    }

    // ── CRA-90 review: fraction invariant ──────────────────────────────────────
    // The KDoc promises fraction ∈ [0.0, 1.0] and not NaN. The `init` block enforces it so
    // producers fail fast rather than emitting incoherent progress to UI consumers.

    @Test
    fun rejectsFractionBelowZero() {
        shouldThrow<IllegalArgumentException> {
            CompressionProgress(CompressionProgress.Phase.COMPRESSING, -0.01f)
        }
    }

    @Test
    fun rejectsFractionAboveOne() {
        shouldThrow<IllegalArgumentException> {
            CompressionProgress(CompressionProgress.Phase.COMPRESSING, 1.01f)
        }
    }

    @Test
    fun rejectsNaNFraction() {
        shouldThrow<IllegalArgumentException> {
            CompressionProgress(CompressionProgress.Phase.COMPRESSING, Float.NaN)
        }
    }

    @Test
    fun acceptsBoundaryZeroAndOne() {
        CompressionProgress(CompressionProgress.Phase.COMPRESSING, 0f).fraction shouldBe 0f
        CompressionProgress(CompressionProgress.Phase.COMPRESSING, 1f).fraction shouldBe 1f
    }

    @Test
    fun copyEnforcesSameInvariant() {
        val p = CompressionProgress(CompressionProgress.Phase.COMPRESSING, 0.5f)
        shouldThrow<IllegalArgumentException> { p.copy(fraction = 2f) }
    }
}
