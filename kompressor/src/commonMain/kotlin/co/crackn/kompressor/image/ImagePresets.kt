package co.crackn.kompressor.image

/** Ready-to-use [ImageCompressionConfig] presets for common use cases. */
object ImagePresets {

    /** Small thumbnail: 320×320, quality 60. */
    val THUMBNAIL = ImageCompressionConfig(
        quality = 60,
        maxWidth = 320,
        maxHeight = 320,
    )

    /** Web-optimised: 1920×1920, quality 80. */
    val WEB = ImageCompressionConfig(
        quality = 80,
        maxWidth = 1920,
        maxHeight = 1920,
    )

    /** High quality: quality 95, original resolution. */
    val HIGH_QUALITY = ImageCompressionConfig(
        quality = 95,
    )
}
