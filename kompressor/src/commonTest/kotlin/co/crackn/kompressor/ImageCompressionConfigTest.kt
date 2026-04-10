package co.crackn.kompressor

import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageCompressionConfigTest {

    @Test
    fun qualityBelow0Throws() {
        assertFailsWith<IllegalArgumentException> {
            ImageCompressionConfig(quality = -1)
        }
    }

    @Test
    fun qualityAbove100Throws() {
        assertFailsWith<IllegalArgumentException> {
            ImageCompressionConfig(quality = 101)
        }
    }

    @Test
    fun maxWidthOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            ImageCompressionConfig(maxWidth = 0)
        }
    }

    @Test
    fun maxHeightOf0Throws() {
        assertFailsWith<IllegalArgumentException> {
            ImageCompressionConfig(maxHeight = 0)
        }
    }

    @Test
    fun validConfigDoesNotThrow() {
        ImageCompressionConfig(quality = 80, maxWidth = 1920, maxHeight = 1080)
    }

    @Test
    fun defaultValues() {
        val config = ImageCompressionConfig()
        assertEquals(ImageFormat.JPEG, config.format)
        assertEquals(80, config.quality)
        assertEquals(null, config.maxWidth)
        assertEquals(null, config.maxHeight)
        assertTrue(config.keepAspectRatio)
    }
}