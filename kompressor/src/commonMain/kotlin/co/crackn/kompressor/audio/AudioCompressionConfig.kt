/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalKompressorApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.ExperimentalKompressorApi

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
 * [co.crackn.kompressor.audio.AudioCompressionError.UnsupportedSourceFormat]. Gated by
 * [ExperimentalKompressorApi] because the multi-track selection semantics (stream-copy
 * restrictions on Android, probe-ordering across containers) are still being stabilised
 * pre-1.0 — see `docs/api-inventory.md`.
 *
 * **Codec restriction (Android only):** when `audioTrackIndex > 0` (or the source has more than
 * one audio track), the Android implementation pre-extracts the selected track to a temporary
 * MP4 by bitstream-copy. MediaMuxer's MP4 container only accepts AAC / AMR-NB / AMR-WB, so
 * multi-track inputs whose selected track is Opus / Vorbis / FLAC / PCM also fail with
 * [co.crackn.kompressor.audio.AudioCompressionError.UnsupportedSourceFormat]. iOS has no such
 * restriction.
 */
public data class AudioCompressionConfig(
    val codec: AudioCodec = AudioCodec.AAC,
    val bitrate: Int = 128_000,
    val sampleRate: Int = 44_100,
    val channels: AudioChannels = AudioChannels.STEREO,
    @property:ExperimentalKompressorApi
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
