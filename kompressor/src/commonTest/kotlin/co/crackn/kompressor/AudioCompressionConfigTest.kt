/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioCompressionConfigTest {

    @Test
    fun bitrateOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            AudioCompressionConfig(bitrate = 0)
        }
    }

    @Test
    fun sampleRateOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            AudioCompressionConfig(sampleRate = 0)
        }
    }

    @Test
    fun validConfigDoesNotThrow() {
        AudioCompressionConfig(bitrate = 128_000, sampleRate = 44_100)
    }

    @Test
    fun defaultValues() {
        val config = AudioCompressionConfig()
        assertEquals(AudioCodec.AAC, config.codec)
        assertEquals(128_000, config.bitrate)
        assertEquals(44_100, config.sampleRate)
        assertEquals(AudioChannels.STEREO, config.channels)
    }
}