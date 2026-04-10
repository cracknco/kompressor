package co.crackn.kompressor.video

/**
 * Maximum output resolution for video compression.
 *
 * Use [Original] to keep the source resolution, or [Custom] with a pixel value
 * for the longest edge. Common presets are available via companion constants.
 */
sealed class MaxResolution {

    /**
     * Custom maximum resolution defined by the longest edge in pixels.
     *
     * @property maxLongEdge Maximum length of the longest edge in pixels.
     */
    data class Custom(val maxLongEdge: Int) : MaxResolution() {
        init {
            require(maxLongEdge > 0) { "maxLongEdge must be > 0, was $maxLongEdge" }
        }
    }

    /** Keep the original video resolution. */
    data object Original : MaxResolution()

    /** Common resolution presets. */
    companion object {
        /** 480p Standard Definition. */
        val SD_480 = Custom(480)

        /** 720p High Definition. */
        val HD_720 = Custom(720)

        /** 1080p Full High Definition. */
        val HD_1080 = Custom(1080)
    }
}
