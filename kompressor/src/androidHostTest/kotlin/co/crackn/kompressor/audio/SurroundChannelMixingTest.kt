/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure host-side coverage of the channel-mixing matrix builders that bridge between Media3's
 * built-in `createForConstantPower` defaults and the hand-rolled ITU-R BS.775 7.1 → mono /
 * stereo / 5.1 coefficients.
 *
 * The matrices themselves are number-dense — these tests pin both the coefficient values and
 * the matrix dimensions so a future refactor can't silently corrupt the downmix.
 */
class SurroundChannelMixingTest {

    @Test
    fun surroundDownmixMatrix_ignoresNon8ChannelInputs() {
        for (input in 1..7) {
            surroundDownmixMatrix(input, 2).shouldBeNull()
        }
    }

    @Test
    fun surroundDownmixMatrix_ignoresUnsupportedOutputChannelCounts() {
        // We ship 8→{1,2,6}. 8→3, 8→4, 8→5, 8→7 fall through to null so callers can route
        // these to the typed UnsupportedConfiguration error rather than silently truncating.
        for (output in listOf(3, 4, 5, 7, 8)) {
            surroundDownmixMatrix(8, output).shouldBeNull()
        }
    }

    @Test
    fun surroundDownmixMatrix_8to1_isMonoFold() {
        val matrix = surroundDownmixMatrix(8, 1).shouldNotBeNull()
        matrix.toList() shouldHaveSize 8
        // Mono fold: FL,FR get 0.7071; FC gets 1.0; LFE gets 0.7071; BL,BR,SL,SR get 0.5.
        val expected = floatArrayOf(0.7071f, 0.7071f, 1.0f, 0.7071f, 0.5f, 0.5f, 0.5f, 0.5f)
        for (i in matrix.indices) matrix[i] shouldBe expected[i]
    }

    @Test
    fun surroundDownmixMatrix_8to2_preservesFrontChannelsExactly() {
        val matrix = surroundDownmixMatrix(8, 2).shouldNotBeNull()
        matrix.toList() shouldHaveSize 16 // 2 outputs × 8 inputs
        // Row 0 (Left): FL=1, FR=0, FC=0.7071, LFE=0.5, BL=0.7071, BR=0, SL=0.7071, SR=0
        matrix[0] shouldBe 1.0f
        matrix[1] shouldBe 0.0f
        matrix[2] shouldBe 0.7071f
        matrix[3] shouldBe 0.5f
        // Row 1 (Right): FL=0, FR=1, FC=0.7071, LFE=0.5, BL=0, BR=0.7071, SL=0, SR=0.7071
        matrix[8] shouldBe 0.0f
        matrix[9] shouldBe 1.0f
        matrix[10] shouldBe 0.7071f
        matrix[11] shouldBe 0.5f
    }

    @Test
    fun surroundDownmixMatrix_8to6_foldsSidesIntoBacks() {
        val matrix = surroundDownmixMatrix(8, 6).shouldNotBeNull()
        matrix.toList() shouldHaveSize 48 // 6 outputs × 8 inputs
        // Front channels pass-through identity (rows 0..3 → outputs 0..3 = inputs 0..3).
        for (output in 0..3) {
            for (input in 0..7) {
                val expected = if (output == input) 1.0f else 0.0f
                matrix[output * 8 + input] shouldBe expected
            }
        }
        // BL row (output 4): BL=1 (input 4) + 0.7071·SL (input 6); BR (input 5) and SR (7) zero.
        matrix[4 * 8 + 4] shouldBe 1.0f
        matrix[4 * 8 + 6] shouldBe 0.7071f
        matrix[4 * 8 + 5] shouldBe 0.0f
        // BR row (output 5): BR=1 (input 5) + 0.7071·SR (input 7).
        matrix[5 * 8 + 5] shouldBe 1.0f
        matrix[5 * 8 + 7] shouldBe 0.7071f
        matrix[5 * 8 + 4] shouldBe 0.0f
    }

    @Test
    fun buildChannelMixingMatrix_passthrough_isIdentity() {
        val matrix = buildChannelMixingMatrix(6, 6)
        for (row in 0 until 6) {
            for (col in 0 until 6) {
                val expected = if (row == col) 1.0f else 0.0f
                matrix.getMixingCoefficient(row, col) shouldBe expected
            }
        }
    }

    @Test
    fun buildChannelMixingMatrix_8to2_usesSurroundMatrix() {
        val matrix = buildChannelMixingMatrix(8, 2)
        matrix.inputChannelCount shouldBe 8
        matrix.outputChannelCount shouldBe 2
        // FL → L coefficient = 1.0 (verifies surroundDownmixMatrix routed, not constant-power).
        matrix.getMixingCoefficient(0, 0) shouldBe 1.0f
        matrix.getMixingCoefficient(2, 0) shouldBe 0.7071f // FC into L
    }

    @Test
    fun buildChannelMixingMatrix_6to2_usesMedia3Defaults() {
        val matrix = buildChannelMixingMatrix(6, 2)
        matrix.inputChannelCount shouldBe 6
        matrix.outputChannelCount shouldBe 2
        // Sanity: Media3's constant-power 5.1→stereo puts 1.0 on the FL→L diagonal.
        matrix.getMixingCoefficient(0, 0) shouldBe 1.0f
    }
}
