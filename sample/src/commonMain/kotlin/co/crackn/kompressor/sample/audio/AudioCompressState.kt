/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.audio

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.io.CompressionProgress

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
    /**
     * Current pipeline phase reflected in the progress label — `MATERIALIZING_INPUT` shows
     * "Preparing…", `COMPRESSING` shows "Compressing", `FINALIZING_OUTPUT` shows "Finalising…".
     * Only consumed when [isCompressing] is `true`; the default is informational and unused at
     * rest.
     */
    val phase: CompressionProgress.Phase = CompressionProgress.Phase.COMPRESSING,
    val isCompressing: Boolean = false,
    val result: CompressionResult? = null,
    val error: String? = null,
)
