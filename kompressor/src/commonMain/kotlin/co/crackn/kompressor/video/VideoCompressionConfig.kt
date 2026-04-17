/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor.video

import co.crackn.kompressor.AudioCodec

/**
 * Configuration for video compression.
 *
 * @property codec Video codec to use. [DynamicRange.HDR10] requires [VideoCodec.HEVC].
 * @property maxResolution Maximum output resolution.
 * @property videoBitrate Target video bitrate in bits per second.
 * @property audioBitrate Target audio bitrate in bits per second.
 * @property audioCodec Audio codec for the output audio track.
 * @property maxFrameRate Maximum frames per second.
 * @property keyFrameInterval Interval between key frames in seconds.
 * @property dynamicRange Output dynamic range. Defaults to [DynamicRange.SDR]. Pick
 *   [DynamicRange.HDR10] together with [VideoCodec.HEVC] to preserve HDR source material;
 *   HDR10 + H.264 is rejected at construction time.
 */
data class VideoCompressionConfig(
    val codec: VideoCodec = VideoCodec.H264,
    val maxResolution: MaxResolution = MaxResolution.HD_720,
    val videoBitrate: Int = 1_200_000,
    val audioBitrate: Int = 128_000,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val maxFrameRate: Int = 30,
    val keyFrameInterval: Int = 2,
    val dynamicRange: DynamicRange = DynamicRange.SDR,
) {
    init {
        require(videoBitrate > 0) { "videoBitrate must be > 0, was $videoBitrate" }
        require(audioBitrate > 0) { "audioBitrate must be > 0, was $audioBitrate" }
        require(maxFrameRate > 0) { "maxFrameRate must be > 0, was $maxFrameRate" }
        require(keyFrameInterval > 0) { "keyFrameInterval must be > 0, was $keyFrameInterval" }
        require(!(dynamicRange == DynamicRange.HDR10 && codec == VideoCodec.H264)) {
            "DynamicRange.HDR10 requires VideoCodec.HEVC — H.264 is 8-bit only"
        }
    }
}
