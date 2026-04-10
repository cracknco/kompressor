package co.crackn.kompressor.video

import co.crackn.kompressor.AudioCodec

/**
 * Configuration for video compression.
 *
 * @property codec Video codec to use.
 * @property maxResolution Maximum output resolution.
 * @property videoBitrate Target video bitrate in bits per second.
 * @property audioBitrate Target audio bitrate in bits per second.
 * @property audioCodec Audio codec for the output audio track.
 * @property maxFrameRate Maximum frames per second.
 * @property keyFrameInterval Interval between key frames in seconds.
 */
data class VideoCompressionConfig(
    val codec: VideoCodec = VideoCodec.H264,
    val maxResolution: MaxResolution = MaxResolution.HD_720,
    val videoBitrate: Int = 1_200_000,
    val audioBitrate: Int = 128_000,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val maxFrameRate: Int = 30,
    val keyFrameInterval: Int = 2,
) {
    init {
        require(videoBitrate > 0) { "videoBitrate must be > 0, was $videoBitrate" }
        require(audioBitrate > 0) { "audioBitrate must be > 0, was $audioBitrate" }
        require(maxFrameRate > 0) { "maxFrameRate must be > 0, was $maxFrameRate" }
        require(keyFrameInterval > 0) { "keyFrameInterval must be > 0, was $keyFrameInterval" }
    }
}
