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

    @Test
    fun monoAt22kHz_belowCap_accepted() {
        // cap = 64 kbps × 1 ch = 64 000
        checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 64_000, sampleRate = 22_050, channels = AudioChannels.MONO))
    }

    @Test
    fun monoAt22kHz_aboveCap_rejected() {
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 96_000, sampleRate = 22_050, channels = AudioChannels.MONO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }

    @Test
    fun stereoAt22kHz_aboveCap_rejected() {
        // cap = 64 kbps × 2 ch = 128 000 — 144 975 was the shrunk failure from CI
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 144_975, sampleRate = 22_050, channels = AudioChannels.STEREO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }

    @Test
    fun monoAt32kHz_aboveCap_rejected() {
        // cap = 96 kbps × 1 ch = 96 000 — 131 254 was the shrunk failure from CI
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 131_254, sampleRate = 32_000, channels = AudioChannels.MONO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }

    @Test
    fun stereoAt44kHz_256kbps_accepted() {
        // cap = 160 kbps × 2 ch = 320 000
        checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 256_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
    }

    @Test
    fun stereoAt44kHz_aboveCap_rejected() {
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 384_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }

    @Test
    fun stereoAt48kHz_320kbps_accepted() {
        // cap = 192 kbps × 2 ch = 384 000
        checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 320_000, sampleRate = 48_000, channels = AudioChannels.STEREO))
    }

    @Test
    fun defaultConfig_accepted() {
        // `AudioCompressionConfig()` defaults (128 kbps @ 44.1 kHz stereo) must pass — this is
        // the most common real-world call site.
        checkSupportedIosBitrate(AudioCompressionConfig())
    }

    // ── Minimum-bitrate floor boundary cases ─────────────────────
    // Apple's AAC-LC encoder rejects bitrates below a per-sample-rate minimum with the same
    // opaque "failed to append sample buffer" it uses for the max cap. These tests pin each
    // floor.

    @Test
    fun stereoAt32kHz_belowMinFloor_rejected() {
        // Min is 24 kbps/channel × 2 = 48 000; 42 986 was the shrunk failure from the iOS CI run.
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 42_986, sampleRate = 32_000, channels = AudioChannels.STEREO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }

    @Test
    fun stereoAt32kHz_atMinFloor_accepted() {
        checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 48_000, sampleRate = 32_000, channels = AudioChannels.STEREO))
    }

    @Test
    fun monoAt22kHz_belowMinFloor_rejected() {
        // Min is 16 kbps for 22 kHz mono; 8 kbps must fail.
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 8_000, sampleRate = 22_050, channels = AudioChannels.MONO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }

    @Test
    fun stereoAt44kHz_belowMinFloor_rejected() {
        // Min is 32 kbps/channel × 2 = 64 000; 32 kbps stereo must fail.
        val err = assertFails {
            checkSupportedIosBitrate(AudioCompressionConfig(bitrate = 32_000, sampleRate = 44_100, channels = AudioChannels.STEREO))
        }
        assertTrue(err is AudioCompressionError.UnsupportedConfiguration, "Expected UnsupportedConfiguration, got $err")
    }
}
