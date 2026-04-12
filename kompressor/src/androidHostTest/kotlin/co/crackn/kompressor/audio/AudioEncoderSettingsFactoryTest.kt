package co.crackn.kompressor.audio

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Host tests for [buildAudioEncoderSettings]. We exercise the pure config → `AudioEncoderSettings`
 * mapping and pin the bitrate plumbing — the most likely vector for a silent regression when
 * touching the audio compressor.
 */
class AudioEncoderSettingsFactoryTest {

    @Test
    fun `settings carry the configured bitrate`() {
        val settings = buildAudioEncoderSettings(AudioCompressionConfig(bitrate = 96_000))
        settings.bitrate shouldBe 96_000
    }

    @Test
    fun `bitrate is propagated verbatim across different values`() {
        listOf(32_000, 64_000, 128_000, 192_000, 256_000).forEach { rate ->
            val settings = buildAudioEncoderSettings(AudioCompressionConfig(bitrate = rate))
            settings.bitrate shouldBe rate
        }
    }
}
