/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoCompressionConfigTest {

    @Test
    fun videoBitrateOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            VideoCompressionConfig(videoBitrate = 0)
        }
    }

    @Test
    fun audioBitrateOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            VideoCompressionConfig(audioBitrate = 0)
        }
    }

    @Test
    fun maxFrameRateOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            VideoCompressionConfig(maxFrameRate = 0)
        }
    }

    @Test
    fun keyFrameIntervalOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            VideoCompressionConfig(keyFrameInterval = 0)
        }
    }

    @Test
    fun customResolutionOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            MaxResolution.Custom(0)
        }
    }

    @Test
    fun customResolutionNegativeThrows() {
        assertFailsWith<IllegalArgumentException> {
            MaxResolution.Custom(-1)
        }
    }

    @Test
    fun validConfigDoesNotThrow() {
        VideoCompressionConfig(videoBitrate = 1_200_000, audioBitrate = 128_000)
    }

    @Test
    fun defaultValues() {
        val config = VideoCompressionConfig()
        assertEquals(VideoCodec.H264, config.codec)
        assertEquals(MaxResolution.HD_720, config.maxResolution)
        assertEquals(1_200_000, config.videoBitrate)
        assertEquals(128_000, config.audioBitrate)
        assertEquals(AudioCodec.AAC, config.audioCodec)
        assertEquals(30, config.maxFrameRate)
        assertEquals(2, config.keyFrameInterval)
    }
}