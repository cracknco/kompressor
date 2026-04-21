/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

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
}
