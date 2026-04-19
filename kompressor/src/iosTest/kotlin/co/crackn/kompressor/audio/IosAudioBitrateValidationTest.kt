/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Direct coverage of [checkSupportedIosBitrate] — the conservative linear per-channel
 * pre-check that fronts `IosAudioCompressor`. The platform is the source of truth; these
 * tests pin the pre-check's boundaries so a stealth tightening (or widening) of the linear
 * model is visible in the diff.
 */
class IosAudioBitrateValidationTest {

    // ── Max-cap boundary tests (linear per-channel model) ─────────

    @Test
    fun monoAt22kHz_atCap_accepted() {
        // cap = 64 kbps × 1 ch = 64 000
        checkSupportedIosBitrate(config(bitrate = 64_000, sampleRate = 22_050, channels = AudioChannels.MONO))
    }

    @Test
    fun monoAt22kHz_aboveCap_rejected() {
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 96_000, sampleRate = 22_050, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt22kHz_aboveCap_rejected() {
        // cap = 64 kbps × 2 ch = 128 000
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 144_975, sampleRate = 22_050, channels = AudioChannels.STEREO))
        }
    }

    @Test
    fun monoAt32kHz_aboveCap_rejected() {
        // cap = 96 kbps × 1 ch = 96 000
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 131_254, sampleRate = 32_000, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt44kHz_atCap_accepted() {
        // cap = 160 kbps × 2 ch = 320 000
        checkSupportedIosBitrate(config(bitrate = 320_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
    }

    @Test
    fun stereoAt44kHz_aboveCap_rejected() {
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 384_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
        }
    }

    @Test
    fun monoAt44kHz_aboveCap_rejected() {
        // cap = 160 kbps × 1 ch = 160 000
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 192_000, sampleRate = 44_100, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt48kHz_atCap_accepted() {
        // cap = 192 kbps × 2 ch = 384 000
        checkSupportedIosBitrate(config(bitrate = 384_000, sampleRate = 48_000, channels = AudioChannels.STEREO))
    }

    @Test
    fun defaultConfig_accepted() {
        checkSupportedIosBitrate(AudioCompressionConfig())
    }

    // ── Minimum-bitrate floor boundary tests ─────────────────────

    @Test
    fun stereoAt32kHz_belowMinFloor_rejected() {
        // min = 24 kbps/ch × 2 = 48 000
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 42_986, sampleRate = 32_000, channels = AudioChannels.STEREO))
        }
    }

    @Test
    fun stereoAt32kHz_atMinFloor_accepted() {
        checkSupportedIosBitrate(config(bitrate = 48_000, sampleRate = 32_000, channels = AudioChannels.STEREO))
    }

    @Test
    fun monoAt22kHz_belowMinFloor_rejected() {
        // min = 16 kbps for 22 kHz mono
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 8_000, sampleRate = 22_050, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt44kHz_belowMinFloor_rejected() {
        // min = 32 kbps/ch × 2 = 64 000
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 32_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
        }
    }

    // ── iosAacMaxBitrate / iosAacMinBitrate direct coverage ──────

    @Test
    fun iosAacMaxBitrate_monoLowRate() {
        assertTrue(iosAacMaxBitrate(22_050, AudioChannels.MONO) == 64_000)
    }

    @Test
    fun iosAacMaxBitrate_monoHighRate() {
        assertTrue(iosAacMaxBitrate(44_100, AudioChannels.MONO) == 160_000)
    }

    @Test
    fun iosAacMaxBitrate_stereoHighRate() {
        assertTrue(iosAacMaxBitrate(44_100, AudioChannels.STEREO) == 320_000)
    }

    @Test
    fun iosAacMinBitrate_stereoMidRate() {
        assertTrue(iosAacMinBitrate(32_000, AudioChannels.STEREO) == 48_000)
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun config(bitrate: Int, sampleRate: Int, channels: AudioChannels) =
        AudioCompressionConfig(bitrate = bitrate, sampleRate = sampleRate, channels = channels)

    private fun assertUnsupportedBitrate(block: () -> Unit) {
        val err = assertFails(block)
        assertTrue(
            err is AudioCompressionError.UnsupportedBitrate,
            "Expected UnsupportedBitrate, got ${err::class.simpleName}: ${err.message}",
        )
    }
}
