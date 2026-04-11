package co.crackn.kompressor.testutil

/** Shared constants used across platform test suites. */
object TestConstants {
    // Audio
    const val SAMPLE_RATE_44K = 44_100
    const val SAMPLE_RATE_48K = 48_000
    const val SAMPLE_RATE_22K = 22_050
    const val STEREO = 2
    const val MONO = 1
    const val DURATION_TOLERANCE_MS = 300L
    const val DURATION_TOLERANCE_SEC = 0.3

    // Video
    const val VIDEO_WIDTH_720P = 1280
    const val VIDEO_HEIGHT_720P = 720
    const val VIDEO_WIDTH_1080P = 1920
    const val VIDEO_HEIGHT_1080P = 1080
    const val VIDEO_WIDTH_480P = 854
    const val VIDEO_HEIGHT_480P = 480
    const val DEFAULT_VIDEO_BITRATE = 1_200_000
    const val DEFAULT_AUDIO_BITRATE = 128_000
    const val DEFAULT_FPS = 30
    const val DEFAULT_KEYFRAME_INTERVAL = 2
}
