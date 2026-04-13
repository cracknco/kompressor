package co.crackn.kompressor

import platform.UIKit.UIDevice
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Simulator smoke test for [queryDeviceCapabilities] on iOS.
 *
 * iOS 15+ guarantees H.264 + HEVC decode/encode and AAC decode/encode through
 * AVFoundation/VideoToolbox; we assert every promised entry surfaces here.
 */
class IosDeviceCapabilitiesTest {

    @Test
    fun queryDeviceCapabilities_includesH264DecoderAndEncoder() {
        val caps = queryDeviceCapabilities()
        val decoders = caps.video.filter { it.mimeType == "video/avc" && it.role == CodecSupport.Role.Decoder }
        val encoders = caps.video.filter { it.mimeType == "video/avc" && it.role == CodecSupport.Role.Encoder }
        assertTrue(decoders.isNotEmpty(), "iOS must report at least one H.264 decoder")
        assertTrue(encoders.isNotEmpty(), "iOS must report at least one H.264 encoder")
        assertTrue(
            encoders.all { it.hardwareAccelerated },
            "iOS H.264 encode runs on VideoToolbox; entries should be hardwareAccelerated=true",
        )
    }

    @Test
    fun queryDeviceCapabilities_includesHevcDecoderAndEncoder() {
        val caps = queryDeviceCapabilities()
        val hevcDecoders = caps.video.filter { it.mimeType == "video/hevc" && it.role == CodecSupport.Role.Decoder }
        val hevcEncoders = caps.video.filter { it.mimeType == "video/hevc" && it.role == CodecSupport.Role.Encoder }
        assertTrue(hevcDecoders.isNotEmpty(), "iOS 15+ guarantees an HEVC decoder")
        assertTrue(hevcEncoders.isNotEmpty(), "iOS 15+ guarantees an HEVC encoder")
        assertTrue(
            hevcDecoders.any { it.supports10Bit },
            "iOS HEVC decoder advertises Main 10 → expected supports10Bit=true on at least one entry",
        )
    }

    @Test
    fun queryDeviceCapabilities_includesAacDecoderAndEncoder() {
        val caps = queryDeviceCapabilities()
        val aacDecoders = caps.audio.filter { it.mimeType == "audio/mp4a-latm" && it.role == CodecSupport.Role.Decoder }
        val aacEncoders = caps.audio.filter { it.mimeType == "audio/mp4a-latm" && it.role == CodecSupport.Role.Encoder }
        assertTrue(aacDecoders.isNotEmpty(), "iOS must report an AAC decoder")
        assertTrue(aacEncoders.isNotEmpty(), "iOS must report an AAC encoder")
    }

    @Test
    fun queryDeviceCapabilities_listsAreNonEmpty() {
        val caps = queryDeviceCapabilities()
        assertTrue(caps.video.isNotEmpty(), "iOS video codec list should never be empty")
        assertTrue(caps.audio.isNotEmpty(), "iOS audio codec list should never be empty")
    }

    @Test
    fun queryDeviceCapabilities_deviceSummaryIncludesModelAndOsVersion() {
        val caps = queryDeviceCapabilities()
        val summary = caps.deviceSummary
        val device = UIDevice.currentDevice
        assertTrue(summary.isNotBlank(), "deviceSummary must be non-blank")
        assertTrue(
            summary.contains(device.model),
            "deviceSummary should contain device.model='${device.model}', was: $summary",
        )
        assertTrue(
            summary.contains(device.systemName),
            "deviceSummary should contain systemName='${device.systemName}', was: $summary",
        )
        assertTrue(
            summary.contains(device.systemVersion),
            "deviceSummary should contain systemVersion='${device.systemVersion}', was: $summary",
        )
    }
}
