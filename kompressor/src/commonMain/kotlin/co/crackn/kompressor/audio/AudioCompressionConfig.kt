package co.crackn.kompressor.audio

import co.crackn.kompressor.AudioCodec

/**
 * Configuration for audio compression.
 *
 * @property codec Audio codec to use.
 * @property bitrate Target bitrate in bits per second.
 * @property sampleRate Sample rate in Hz.
 * @property channels Output channel layout.
 * @property audioTrackIndex Zero-based index of the audio track to compress when the source has
 * multiple audio tracks. Defaults to the first track (`0`). If the source has fewer tracks than
 * `audioTrackIndex + 1`, compression fails with
 * [co.crackn.kompressor.audio.AudioCompressionError.UnsupportedSourceFormat].
 */
public data class AudioCompressionConfig(
    val codec: AudioCodec = AudioCodec.AAC,
    val bitrate: Int = 128_000,
    val sampleRate: Int = 44_100,
    val channels: AudioChannels = AudioChannels.STEREO,
    val audioTrackIndex: Int = 0,
) {
    init {
        require(bitrate > 0) { "bitrate must be > 0, was $bitrate" }
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
        require(audioTrackIndex >= 0) {
            "audioTrackIndex must be >= 0, was $audioTrackIndex"
        }
    }
}
