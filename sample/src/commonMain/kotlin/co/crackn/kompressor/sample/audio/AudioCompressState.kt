package co.crackn.kompressor.sample.audio

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.audio.AudioChannels

enum class AudioPresetOption { VOICE_MESSAGE, PODCAST, HIGH_QUALITY, CUSTOM }

data class AudioCompressState(
    val selectedAudioPath: String? = null,
    val selectedFileName: String? = null,
    val compressedAudioPath: String? = null,
    val selectedPreset: AudioPresetOption = AudioPresetOption.PODCAST,
    val customBitrate: String = "128",
    val customSampleRate: Int = 44_100,
    val customChannels: AudioChannels = AudioChannels.STEREO,
    val progress: Float = 0f,
    val isCompressing: Boolean = false,
    val result: CompressionResult? = null,
    val error: String? = null,
)
