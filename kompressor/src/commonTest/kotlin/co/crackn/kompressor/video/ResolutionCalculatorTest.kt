/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResolutionCalculatorTest {

    @Test
    fun originalResolutionPreservesSourceDimensions() {
        val (w, h) = ResolutionCalculator.calculate(1920, 1080, MaxResolution.Original)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun originalResolutionRoundsOddDimensionsToEven() {
        val (w, h) = ResolutionCalculator.calculate(1921, 1081, MaxResolution.Original)
        assertEquals(1922, w)
        assertEquals(1082, h)
    }

    @Test
    fun downscaleTo720pLandscape() {
        val (w, h) = ResolutionCalculator.calculate(1920, 1080, MaxResolution.HD_720)
        // Short edge is 1080, target 720 → scale = 720/1080 = 0.667
        // 1920 * 0.667 = 1280, 1080 * 0.667 = 720
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun downscaleTo720pPortrait() {
        val (w, h) = ResolutionCalculator.calculate(1080, 1920, MaxResolution.HD_720)
        assertEquals(720, w)
        assertEquals(1280, h)
    }

    @Test
    fun noUpscaleWhenSourceSmallerThanTarget() {
        val (w, h) = ResolutionCalculator.calculate(640, 480, MaxResolution.HD_720)
        assertEquals(640, w)
        assertEquals(480, h)
    }

    @Test
    fun noUpscaleWhenSourceEqualsTarget() {
        val (w, h) = ResolutionCalculator.calculate(1280, 720, MaxResolution.HD_720)
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun downscaleTo480p() {
        val (w, h) = ResolutionCalculator.calculate(1920, 1080, MaxResolution.SD_480)
        // 480/1080 = 0.444 → 1920*0.444 ≈ 853 → round DOWN to even = 852, 480
        // (rounding up would violate the Custom contract when maxShortEdge is odd)
        assertEquals(852, w)
        assertEquals(480, h)
    }

    @Test
    fun customResolution() {
        val (w, h) = ResolutionCalculator.calculate(3840, 2160, MaxResolution.Custom(540))
        // 540/2160 = 0.25 → 3840*0.25=960, 2160*0.25=540
        assertEquals(960, w)
        assertEquals(540, h)
    }

    @Test
    fun outputDimensionsAreAlwaysEven() {
        // 1280x720 → scale to 360: 1280 * 360/720 = 640, 360 — both even ✓
        val (w, h) = ResolutionCalculator.calculate(1280, 720, MaxResolution.Custom(360))
        assertTrue(w % 2 == 0, "Width $w must be even")
        assertTrue(h % 2 == 0, "Height $h must be even")
    }

    @Test
    fun zeroSourceWidthThrows() {
        assertFailsWith<IllegalArgumentException> {
            ResolutionCalculator.calculate(0, 1080, MaxResolution.HD_720)
        }
    }

    @Test
    fun negativeSourceHeightThrows() {
        assertFailsWith<IllegalArgumentException> {
            ResolutionCalculator.calculate(1920, -1, MaxResolution.HD_720)
        }
    }

    @Test
    fun squareVideoDownscale() {
        val (w, h) = ResolutionCalculator.calculate(1080, 1080, MaxResolution.HD_720)
        // Short edge = 1080, target 720 → scale = 720/1080 = 0.667 → 720x720
        assertEquals(720, w)
        assertEquals(720, h)
    }
}
