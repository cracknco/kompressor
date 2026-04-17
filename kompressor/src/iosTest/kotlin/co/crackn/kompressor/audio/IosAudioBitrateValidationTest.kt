/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Direct coverage of [checkSupportedIosBitrate] — the bitrate / sample-rate / channel-count
 * validation table that fronts `IosAudioCompressor` and protects callers from opaque
 * `AVAssetWriterInput failed to append sample buffer` errors. Without these assertions the
 * property test's broadened range could silently pass even if the caps regressed (e.g. a
 * future commit doubling every cap would never trigger a property-test failure because all
 * random configs would succeed — we'd lose the gate entirely).
 */
class IosAudioBitrateValidationTest {

    // ── Mono / stereo max-cap boundary tests ─────────────────────

    @Test
    fun monoAt22kHz_belowCap_accepted() {
        // cap = 64 kbps x 1 ch = 64 000
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
        // cap = 64 kbps x 2 ch = 128 000 — 144 975 was the shrunk failure from CI
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 144_975, sampleRate = 22_050, channels = AudioChannels.STEREO))
        }
    }

    @Test
    fun monoAt32kHz_aboveCap_rejected() {
        // cap = 96 kbps x 1 ch = 96 000 — 131 254 was the shrunk failure from CI
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 131_254, sampleRate = 32_000, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt44kHz_256kbps_accepted() {
        // cap = 160 kbps x 2 ch = 320 000
        checkSupportedIosBitrate(config(bitrate = 256_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
    }

    @Test
    fun stereoAt44kHz_aboveCap_rejected() {
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 384_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
        }
    }

    @Test
    fun monoAt44kHz_atEmpiricalCap_accepted() {
        // Empirical override: mono at 44.1 kHz accepts up to 256 kbps (Device Farm run 24536970778).
        checkSupportedIosBitrate(config(bitrate = 256_000, sampleRate = 44_100, channels = AudioChannels.MONO))
    }

    @Test
    fun monoAt44kHz_aboveEmpiricalCap_rejected() {
        // 288 kbps is the first rejected bitrate in the Device Farm sweep — assert the cap is
        // the empirical 256k ceiling, not the old 160k linear projection.
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 288_000, sampleRate = 44_100, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt48kHz_320kbps_accepted() {
        // cap = 192 kbps x 2 ch = 384 000
        checkSupportedIosBitrate(config(bitrate = 320_000, sampleRate = 48_000, channels = AudioChannels.STEREO))
    }

    @Test
    fun defaultConfig_accepted() {
        checkSupportedIosBitrate(AudioCompressionConfig())
    }

    // ── Minimum-bitrate floor boundary tests ─────────────────────

    @Test
    fun stereoAt32kHz_belowMinFloor_rejected() {
        // Min is 24 kbps/channel x 2 = 48 000; 42 986 was the shrunk failure from the iOS CI run.
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
        // Min is 16 kbps for 22 kHz mono; 8 kbps must fail.
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 8_000, sampleRate = 22_050, channels = AudioChannels.MONO))
        }
    }

    @Test
    fun stereoAt44kHz_belowMinFloor_rejected() {
        // Min is 32 kbps/channel x 2 = 64 000; 32 kbps stereo must fail.
        assertUnsupportedBitrate {
            checkSupportedIosBitrate(config(bitrate = 32_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
        }
    }

    // ── Surround (5.1 / 7.1) rejection tests ─────────────────────
    // Device Farm run 24536970778 (iPhone 13 / A15 / iOS 18) confirmed AudioToolbox rejects
    // surround AAC at every tested bitrate (32k–1280k). These tests pin the blanket rejection.

    @Test
    fun fivePointOneAt44kHz_anyBitrate_unsupportedConfiguration() {
        assertUnsupportedConfiguration {
            checkSupportedIosBitrate(
                config(bitrate = 512_000, sampleRate = 44_100, channels = AudioChannels.FIVE_POINT_ONE),
            )
        }
    }

    @Test
    fun fivePointOneAt22kHz_anyBitrate_unsupportedConfiguration() {
        assertUnsupportedConfiguration {
            checkSupportedIosBitrate(
                config(bitrate = 64_000, sampleRate = 22_050, channels = AudioChannels.FIVE_POINT_ONE),
            )
        }
    }

    @Test
    fun sevenPointOneAt44kHz_anyBitrate_unsupportedConfiguration() {
        assertUnsupportedConfiguration {
            checkSupportedIosBitrate(
                config(bitrate = 768_000, sampleRate = 44_100, channels = AudioChannels.SEVEN_POINT_ONE),
            )
        }
    }

    @Test
    fun sevenPointOneAt48kHz_anyBitrate_unsupportedConfiguration() {
        assertUnsupportedConfiguration {
            checkSupportedIosBitrate(
                config(bitrate = 384_000, sampleRate = 48_000, channels = AudioChannels.SEVEN_POINT_ONE),
            )
        }
    }

    // ── iosAacMaxBitrate / iosAacMinBitrate direct coverage ──────

    @Test
    fun iosAacMaxBitrate_monoLowRate() {
        val max = iosAacMaxBitrate(22_050, AudioChannels.MONO)
        assertTrue(max == 64_000, "Expected 64000, got $max")
    }

    @Test
    fun iosAacMaxBitrate_monoHighRate_usesEmpiricalOverride() {
        // Mono-at-44.1kHz is a non-linear cell — the linear model would predict 160k, but
        // the Device Farm run proves AudioToolbox accepts up to 256k.
        val max = iosAacMaxBitrate(44_100, AudioChannels.MONO)
        assertTrue(max == 256_000, "Expected 256000 (empirical mono @ 44.1 kHz), got $max")
    }

    @Test
    fun iosAacMaxBitrate_monoAt32kHz_doesNotUseOverride() {
        // Guard is pinned to sampleRate == 44_100 — 32 kHz mono must fall through to the
        // linear MID-tier cap (96 kbps/ch × 1 = 96 000). If someone later widens the guard
        // without re-sweeping 32 kHz on device, this assertion flips red.
        val max = iosAacMaxBitrate(32_000, AudioChannels.MONO)
        assertTrue(max == 96_000, "Expected 96000 (linear 32 kHz mono), got $max")
    }

    @Test
    fun iosAacMaxBitrate_monoAt48kHz_doesNotUseOverride() {
        // 48 kHz mono sits in the VERY_HIGH tier — must use the linear 192 kbps/ch × 1 cap,
        // not the empirical 256 kbps 44.1 kHz override.
        val max = iosAacMaxBitrate(48_000, AudioChannels.MONO)
        assertTrue(max == 192_000, "Expected 192000 (linear 48 kHz mono), got $max")
    }

    @Test
    fun iosAacMaxBitrate_stereoHighRate() {
        val max = iosAacMaxBitrate(44_100, AudioChannels.STEREO)
        assertTrue(max == 320_000, "Expected 320000, got $max")
    }

    @Test
    fun iosAacMaxBitrate_fivePointOne_alwaysZero() {
        val max = iosAacMaxBitrate(48_000, AudioChannels.FIVE_POINT_ONE)
        assertTrue(max == 0, "Expected 0 (surround unsupported), got $max")
    }

    @Test
    fun iosAacMinBitrate_stereoMidRate() {
        val min = iosAacMinBitrate(32_000, AudioChannels.STEREO)
        assertTrue(min == 48_000, "Expected 48000, got $min")
    }

    @Test
    fun iosAacMinBitrate_fivePointOne_alwaysZero() {
        val min = iosAacMinBitrate(44_100, AudioChannels.FIVE_POINT_ONE)
        assertTrue(min == 0, "Expected 0 (surround unsupported), got $min")
    }

    @Test
    fun iosAacMinBitrate_sevenPointOne_alwaysZero() {
        val min = iosAacMinBitrate(44_100, AudioChannels.SEVEN_POINT_ONE)
        assertTrue(min == 0, "Expected 0 (surround unsupported), got $min")
    }

    @Test
    fun iosAacMaxBitrate_sevenPointOne_alwaysZero() {
        val max = iosAacMaxBitrate(44_100, AudioChannels.SEVEN_POINT_ONE)
        assertTrue(max == 0, "Expected 0 (surround unsupported), got $max")
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

    private fun assertUnsupportedConfiguration(block: () -> Unit) {
        val err = assertFails(block)
        assertTrue(
            err is AudioCompressionError.UnsupportedConfiguration,
            "Expected UnsupportedConfiguration, got ${err::class.simpleName}: ${err.message}",
        )
    }
}
