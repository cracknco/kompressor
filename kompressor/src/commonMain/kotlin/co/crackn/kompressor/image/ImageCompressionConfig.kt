package co.crackn.kompressor.image

/**
 * Configuration for image compression.
 *
 * @property format Output image format.
 * @property quality Compression quality (0–100). Higher means better quality but larger file.
 * @property maxWidth Maximum width in pixels. `null` keeps the original width.
 * @property maxHeight Maximum height in pixels. `null` keeps the original height.
 * @property keepAspectRatio Whether to preserve the aspect ratio when resizing.
 */
data class ImageCompressionConfig(
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 80,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val keepAspectRatio: Boolean = true,
) {
    init {
        require(quality in 0..100) { "quality must be between 0 and 100, was $quality" }
        maxWidth?.let { require(it > 0) { "maxWidth must be > 0, was $it" } }
        maxHeight?.let { require(it > 0) { "maxHeight must be > 0, was $it" } }
    }
}
