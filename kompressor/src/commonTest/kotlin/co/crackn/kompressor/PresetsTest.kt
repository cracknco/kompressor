/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.image.ImagePresets
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoPresets
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetsTest {

    // ── Image ──

    @Test
    fun imageThumbnailPreset() {
        assertEquals(320, ImagePresets.THUMBNAIL.maxWidth)
        assertEquals(320, ImagePresets.THUMBNAIL.maxHeight)
        assertEquals(60, ImagePresets.THUMBNAIL.quality)
    }

    @Test
    fun imageWebPreset() {
        assertEquals(1920, ImagePresets.WEB.maxWidth)
        assertEquals(80, ImagePresets.WEB.quality)
    }

    @Test
    fun imageHighQualityPreset() {
        assertEquals(95, ImagePresets.HIGH_QUALITY.quality)
        assertEquals(null, ImagePresets.HIGH_QUALITY.maxWidth)
        assertEquals(null, ImagePresets.HIGH_QUALITY.maxHeight)
    }

    // ── Video ──

    @Test
    fun videoMessagingPreset() {
        assertEquals(1_200_000, VideoPresets.MESSAGING.videoBitrate)
        assertEquals(MaxResolution.HD_720, VideoPresets.MESSAGING.maxResolution)
    }

    @Test
    fun videoHighQualityPreset() {
        assertEquals(MaxResolution.HD_1080, VideoPresets.HIGH_QUALITY.maxResolution)
        assertEquals(3_500_000, VideoPresets.HIGH_QUALITY.videoBitrate)
        assertEquals(192_000, VideoPresets.HIGH_QUALITY.audioBitrate)
    }

    @Test
    fun videoLowBandwidthPreset() {
        assertEquals(MaxResolution.SD_480, VideoPresets.LOW_BANDWIDTH.maxResolution)
        assertEquals(600_000, VideoPresets.LOW_BANDWIDTH.videoBitrate)
    }

    // ── Audio ──

    @Test
    fun audioVoiceMessagePreset() {
        assertEquals(AudioChannels.MONO, AudioPresets.VOICE_MESSAGE.channels)
        assertEquals(32_000, AudioPresets.VOICE_MESSAGE.bitrate)
        assertEquals(22_050, AudioPresets.VOICE_MESSAGE.sampleRate)
    }

    @Test
    fun audioPodcastPreset() {
        assertEquals(96_000, AudioPresets.PODCAST.bitrate)
        assertEquals(44_100, AudioPresets.PODCAST.sampleRate)
        assertEquals(AudioChannels.STEREO, AudioPresets.PODCAST.channels)
    }

    @Test
    fun audioHighQualityPreset() {
        assertEquals(192_000, AudioPresets.HIGH_QUALITY.bitrate)
        assertEquals(44_100, AudioPresets.HIGH_QUALITY.sampleRate)
    }
}