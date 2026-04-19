/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.os.Build
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Device-side smoke test for [queryDeviceCapabilities] on Android.
 *
 * Asserts the capability matrix contains the codecs every API 24+ device must ship:
 *  * H.264 (`video/avc`) decoder + encoder.
 *  * AAC (`audio/mp4a-latm`) decoder + encoder.
 *
 * Also verifies [DeviceCapabilities.deviceSummary] includes the manufacturer/model and
 * Android API level — used by the sample app's Capabilities screen and bug reports.
 */
class AndroidDeviceCapabilitiesTest {

    @Test
    fun queryDeviceCapabilities_includesH264DecoderAndEncoder() {
        val caps = queryDeviceCapabilities()

        val h264Decoders = caps.video.filter { it.mimeType == "video/avc" && it.role == CodecSupport.Role.Decoder }
        val h264Encoders = caps.video.filter { it.mimeType == "video/avc" && it.role == CodecSupport.Role.Encoder }
        assertTrue(h264Decoders.isNotEmpty(), "Every Android device ships an H.264 decoder; got none")
        assertTrue(h264Encoders.isNotEmpty(), "Every Android device ships an H.264 encoder; got none")

        // codecName must be populated on Android (we surface MediaCodecInfo.name verbatim).
        h264Encoders.forEach { entry ->
            val name = entry.codecName
            assertNotNull(name, "Android H.264 encoder should expose codecName")
            assertTrue(name.isNotBlank(), "Android H.264 encoder codecName must be non-blank")
        }
    }

    @Test
    fun queryDeviceCapabilities_includesAacDecoderAndEncoder() {
        val caps = queryDeviceCapabilities()

        val aacDecoders = caps.audio.filter { it.mimeType == "audio/mp4a-latm" && it.role == CodecSupport.Role.Decoder }
        val aacEncoders = caps.audio.filter { it.mimeType == "audio/mp4a-latm" && it.role == CodecSupport.Role.Encoder }
        assertTrue(aacDecoders.isNotEmpty(), "Every Android device ships an AAC decoder; got none")
        assertTrue(aacEncoders.isNotEmpty(), "Every Android device ships an AAC encoder; got none")

        aacEncoders.forEach { entry ->
            val name = entry.codecName
            assertNotNull(name, "Android AAC encoder should expose codecName")
            assertTrue(name.isNotBlank(), "Android AAC encoder codecName must be non-blank")
        }
    }

    @Test
    fun queryDeviceCapabilities_videoAndAudioListsAreNonEmpty() {
        val caps = queryDeviceCapabilities()
        assertTrue(caps.video.isNotEmpty(), "video codec list should never be empty on Android")
        assertTrue(caps.audio.isNotEmpty(), "audio codec list should never be empty on Android")
    }

    @Test
    fun queryDeviceCapabilities_deviceSummaryIncludesManufacturerModelAndApi() {
        val caps = queryDeviceCapabilities()
        val summary = caps.deviceSummary

        assertTrue(summary.isNotBlank(), "deviceSummary must be non-blank")
        assertTrue(
            summary.contains(Build.MANUFACTURER),
            "deviceSummary should contain MANUFACTURER='${Build.MANUFACTURER}', was: $summary",
        )
        assertTrue(
            summary.contains(Build.MODEL),
            "deviceSummary should contain MODEL='${Build.MODEL}', was: $summary",
        )
        assertTrue(
            summary.contains("API ${Build.VERSION.SDK_INT}"),
            "deviceSummary should contain 'API ${Build.VERSION.SDK_INT}', was: $summary",
        )
    }
}
