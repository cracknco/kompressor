/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class SupportabilityTest {

    private val h264Decoder = CodecSupport(
        mimeType = "video/avc",
        role = CodecSupport.Role.Decoder,
        profiles = listOf("High"),
    )
    private val h264Encoder = CodecSupport(
        mimeType = "video/avc",
        role = CodecSupport.Role.Encoder,
        profiles = listOf("High"),
    )
    private val hevcDecoderMainOnly = CodecSupport(
        mimeType = "video/hevc",
        role = CodecSupport.Role.Decoder,
        profiles = listOf("Main"),
        supports10Bit = false,
    )
    private val aacDecoder = CodecSupport(
        mimeType = "audio/mp4a-latm",
        role = CodecSupport.Role.Decoder,
    )
    private val aacEncoder = CodecSupport(
        mimeType = "audio/mp4a-latm",
        role = CodecSupport.Role.Encoder,
    )

    private fun capabilitiesFor(video: List<CodecSupport>, audio: List<CodecSupport>) =
        DeviceCapabilities(video = video, audio = audio, deviceSummary = "test")

    @Test
    fun supportedWhenEveryRequiredCodecPresent() {
        val caps = capabilitiesFor(
            video = listOf(h264Decoder, h264Encoder),
            audio = listOf(aacDecoder, aacEncoder),
        )
        val info = SourceMediaInfo(
            videoCodec = "video/avc",
            bitDepth = 8,
            audioCodec = "audio/mp4a-latm",
        )
        evaluateSupport(info, caps, MIME_VIDEO_H264, MIME_AUDIO_AAC) shouldBe Supportability.Supported
    }

    @Test
    fun unsupportedWhenDecoderMissing() {
        val caps = capabilitiesFor(
            video = listOf(h264Encoder),
            audio = listOf(aacDecoder, aacEncoder),
        )
        val info = SourceMediaInfo(videoCodec = "video/hevc")
        val verdict = evaluateSupport(info, caps, MIME_VIDEO_H264, MIME_AUDIO_AAC)
        verdict.shouldBeInstanceOf<Supportability.Unsupported>()
        verdict.reasons.size shouldBe 1
    }

    @Test
    fun unsupportedWhenPlatformFlagsNotPlayable() {
        val caps = capabilitiesFor(
            video = listOf(h264Decoder, h264Encoder),
            audio = listOf(aacDecoder, aacEncoder),
        )
        val info = SourceMediaInfo(isPlayable = false)
        evaluateSupport(info, caps, MIME_VIDEO_H264, MIME_AUDIO_AAC)
            .shouldBeInstanceOf<Supportability.Unsupported>()
    }

    @Test
    fun unknownWhenBitDepthMissingOnHEVCWith8bitonlyDecoder() {
        val caps = capabilitiesFor(
            video = listOf(hevcDecoderMainOnly, h264Encoder),
            audio = listOf(aacDecoder, aacEncoder),
        )
        val info = SourceMediaInfo(videoCodec = "video/hevc", bitDepth = null)
        evaluateSupport(info, caps, MIME_VIDEO_H264, MIME_AUDIO_AAC)
            .shouldBeInstanceOf<Supportability.Unknown>()
    }

    @Test
    fun unsupportedWhenSourceResolutionExceedsDecoderMax() {
        val h264Big = h264Decoder.copy(maxResolution = 1920 to 1080)
        val caps = capabilitiesFor(
            video = listOf(h264Big, h264Encoder),
            audio = listOf(aacDecoder, aacEncoder),
        )
        val info = SourceMediaInfo(videoCodec = "video/avc", width = 3840, height = 2160, bitDepth = 8)
        evaluateSupport(info, caps, MIME_VIDEO_H264, MIME_AUDIO_AAC)
            .shouldBeInstanceOf<Supportability.Unsupported>()
    }

    @Test
    fun unsupportedWhen10bitNeededButDecoderIs8bitOnly() {
        val caps = capabilitiesFor(
            video = listOf(hevcDecoderMainOnly, h264Encoder),
            audio = listOf(aacDecoder, aacEncoder),
        )
        val info = SourceMediaInfo(
            videoCodec = "video/hevc",
            videoProfile = "Main 10",
            bitDepth = 10,
        )
        val verdict = evaluateSupport(info, caps, MIME_VIDEO_H264, MIME_AUDIO_AAC)
        verdict.shouldBeInstanceOf<Supportability.Unsupported>()
    }
}
