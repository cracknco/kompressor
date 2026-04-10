package co.crackn.kompressor.video

/**
 * Maximum output resolution for video compression.
 *
 * Use [Original] to keep the source resolution, or [Custom] with a pixel value
 * for the shortest edge (height in landscape). Common presets are available via companion constants.
 */
sealed class MaxResolution {

    /**
     * Custom maximum resolution defined by the shortest edge in pixels.
     *
     * For a landscape 16:9 video this corresponds to the height (e.g. 1080 for 1080p).
     *
     * @property maxShortEdge Maximum length of the shortest edge in pixels.
     */
    data class Custom(val maxShortEdge: Int) : MaxResolution() {
        init {
            require(maxShortEdge > 0) { "maxShortEdge must be > 0, was $maxShortEdge" }
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
