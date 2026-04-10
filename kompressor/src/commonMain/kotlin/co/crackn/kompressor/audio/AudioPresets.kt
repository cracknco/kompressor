package co.crackn.kompressor.audio

import co.crackn.kompressor.AudioCodec

/** Ready-to-use [AudioCompressionConfig] presets for common use cases. */
object AudioPresets {

    /** Voice message: mono, 32 kbps, 22.05 kHz — small file size for speech. */
    val VOICE_MESSAGE = AudioCompressionConfig(
        codec = AudioCodec.AAC,
        bitrate = 32_000,
        sampleRate = 22_050,
        channels = AudioChannels.MONO,
    )

    /** Podcast: stereo, 96 kbps, 44.1 kHz — good balance of quality and size. */
    val PODCAST = AudioCompressionConfig(
        codec = AudioCodec.AAC,
        bitrate = 96_000,
        sampleRate = 44_100,
    )

    /** High quality: stereo, 192 kbps, 44.1 kHz — near-transparent quality. */
    val HIGH_QUALITY = AudioCompressionConfig(
        codec = AudioCodec.AAC,
        bitrate = 192_000,
        sampleRate = 44_100,
    )
}
