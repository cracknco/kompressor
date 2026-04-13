package co.crackn.kompressor.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Contract tests for the [VideoCompressionConfig] HDR cross-field guard.
 *
 * HDR10 output is 10-bit — H.264 Main / High is 8-bit only — so any combination of
 * [DynamicRange.HDR10] with [VideoCodec.H264] must fail at construction time. The guard lives
 * in [VideoCompressionConfig.init]; these tests pin the failure modes so a future commit that
 * loosens the guard (e.g. silently coerces the codec) is caught at build time rather than
 * producing a corrupt HEVC-in-8bit stream on device.
 */
class VideoCompressionConfigHdrTest {

    @Test
    fun defaultConfig_isSdr_andH264() {
        val config = VideoCompressionConfig()
        assertEquals(DynamicRange.SDR, config.dynamicRange)
        assertEquals(VideoCodec.H264, config.codec)
    }

    @Test
    fun hdr10_withHevc_constructsCleanly() {
        val config = VideoCompressionConfig(codec = VideoCodec.HEVC, dynamicRange = DynamicRange.HDR10)
        assertEquals(DynamicRange.HDR10, config.dynamicRange)
        assertEquals(VideoCodec.HEVC, config.codec)
    }

    @Test
    fun hdr10_withH264_rejectedAtConstruct() {
        val err = assertFails {
            VideoCompressionConfig(codec = VideoCodec.H264, dynamicRange = DynamicRange.HDR10)
        }
        assertTrue(err is IllegalArgumentException, "Expected IllegalArgumentException, got $err")
        assertTrue(
            err.message?.contains("HDR10") == true && err.message?.contains("HEVC") == true,
            "Error message must name both the offending dynamic range and the required codec: ${err.message}",
        )
    }

    @Test
    fun sdr_withHevc_constructsCleanly() {
        val config = VideoCompressionConfig(codec = VideoCodec.HEVC, dynamicRange = DynamicRange.SDR)
        assertEquals(DynamicRange.SDR, config.dynamicRange)
        assertEquals(VideoCodec.HEVC, config.codec)
    }

    @Test
    fun sdr_withH264_constructsCleanly() {
        val config = VideoCompressionConfig(codec = VideoCodec.H264, dynamicRange = DynamicRange.SDR)
        assertEquals(DynamicRange.SDR, config.dynamicRange)
        assertEquals(VideoCodec.H264, config.codec)
    }

    @Test
    fun hdr10Preset_isHevcHdr10() {
        val preset = VideoPresets.HDR10_1080P
        assertEquals(DynamicRange.HDR10, preset.dynamicRange)
        assertEquals(VideoCodec.HEVC, preset.codec)
    }
}
