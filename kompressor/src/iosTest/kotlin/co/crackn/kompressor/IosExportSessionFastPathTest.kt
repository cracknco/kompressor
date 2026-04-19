/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class, co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.audio.canUseAudioExportSession
import co.crackn.kompressor.testutil.MultiTrackAudioFixture
import co.crackn.kompressor.video.DynamicRange
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCodec
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.exportSessionPresetOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetExportPresetHEVCHighestQuality
import platform.AVFoundation.AVAssetExportPresetMediumQuality
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * Fast-path eligibility + behaviour tests for iOS's `AVAssetExportSession` code path.
 *
 * Two dimensions are covered here:
 *
 *  - **Eligibility predicates** ([exportSessionPresetOrNull] / [canUseAudioExportSession]) —
 *    these are pure functions of the caller's config and must match the preset they imply. A
 *    regression here silently drops the fast path back to the custom transcode pipeline (or,
 *    worse, picks a preset that doesn't match the config).
 *  - **Behaviour** — exercising the audio fast path with a `audioTrackIndex > 0` input still
 *    selects the requested track (proved by frequency analysis of the output). The HDR10 video
 *    round-trip needs physical A10+ hardware, so it lives in
 *    [co.crackn.kompressor.video.Hdr10ExportRoundTripTest].
 */
@OptIn(ExperimentalForeignApi::class)
class IosExportSessionFastPathTest {

    private lateinit var testDir: String
    private val audioCompressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-fastpath-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    // ── Video eligibility ──────────────────────────────────────────────

    @Test
    fun videoFastPath_defaultConfig_picksMediumQualityPreset() {
        val preset = exportSessionPresetOrNull(VideoCompressionConfig())
        assertEquals(AVAssetExportPresetMediumQuality, preset)
    }

    @Test
    fun videoFastPath_hdr10HevcDefaults_picksHevcHighestQualityPreset() {
        // HDR10 + HEVC with every other field at default must light up the HEVC preset. The
        // preset preserves HDR10 color metadata (BT.2020 + PQ + BT.2020 matrix) when the source
        // is HDR10; SDR sources are transcoded as SDR HEVC — consistent behavior either way.
        val preset = exportSessionPresetOrNull(
            VideoCompressionConfig(codec = VideoCodec.HEVC, dynamicRange = DynamicRange.HDR10),
        )
        assertEquals(AVAssetExportPresetHEVCHighestQuality, preset)
    }

    @Test
    fun videoFastPath_hdr10WithCustomBitrate_fallsBackToTranscode() {
        // Any knob outside the HDR10 defaults means AVAssetExportSession can't honour the
        // request — only the custom transcode pipeline can apply a specific bitrate — so the
        // eligibility predicate must say null.
        val preset = exportSessionPresetOrNull(
            VideoCompressionConfig(
                codec = VideoCodec.HEVC,
                dynamicRange = DynamicRange.HDR10,
                videoBitrate = 8_000_000,
            ),
        )
        assertNull(preset)
    }

    @Test
    fun videoFastPath_hdr10WithCustomResolution_fallsBackToTranscode() {
        val preset = exportSessionPresetOrNull(
            VideoCompressionConfig(
                codec = VideoCodec.HEVC,
                dynamicRange = DynamicRange.HDR10,
                maxResolution = MaxResolution.SD_480,
            ),
        )
        assertNull(preset)
    }

    @Test
    fun videoFastPath_sdrHevc_fallsBackToTranscode() {
        // HEVC + SDR is not a fast-path combo — the custom pipeline must drive the H264/HEVC
        // switch via its settings dictionary. This pins the regression that would otherwise
        // route SDR HEVC through the HDR10 preset (wrong color metadata).
        val preset = exportSessionPresetOrNull(VideoCompressionConfig(codec = VideoCodec.HEVC))
        assertNull(preset)
    }

    @Test
    fun videoFastPath_customBitrate_fallsBackToTranscode() {
        val preset = exportSessionPresetOrNull(VideoCompressionConfig(videoBitrate = 500_000))
        assertNull(preset)
    }

    // ── Audio eligibility ──────────────────────────────────────────────

    @Test
    fun audioFastPath_defaultConfig_eligible() {
        assertTrue(canUseAudioExportSession(AudioCompressionConfig()))
    }

    @Test
    fun audioFastPath_nonDefaultTrackIndex_eligible() {
        // The whole point of this ticket: `audioTrackIndex > 0` must stay in the fast path.
        assertTrue(canUseAudioExportSession(AudioCompressionConfig(audioTrackIndex = 3)))
    }

    @Test
    fun audioFastPath_customBitrate_falsBackToTranscode() {
        // Any other field outside the default forces the custom pipeline because
        // AVAssetExportPresetAppleM4A ignores caller-supplied bitrate/sampleRate/channels.
        assertTrue(!canUseAudioExportSession(AudioCompressionConfig(bitrate = 64_000)))
    }

    @Test
    fun audioFastPath_customChannels_fallsBackToTranscode() {
        assertTrue(!canUseAudioExportSession(AudioCompressionConfig(channels = AudioChannels.MONO)))
    }

    // ── Audio fast path behaviour: track selection via AVAudioMix ─────

    @Test
    fun audioFastPath_selectsNonDefaultTrack_byVolumeMuting() = runTest {
        // End-to-end verification of the new AVMutableAudioMix behaviour on the fast path: a
        // two-track MP4 with 440 Hz / 880 Hz on tracks 0 / 1 (both stereo so the default STEREO
        // config clears the upmix pre-flight) must, when compressed with `audioTrackIndex = 1`
        // and an otherwise default config — so fast-path eligibility stays ON — yield an output
        // whose dominant tone is 880 Hz. That proves the mix muted track 0 rather than summing
        // both tracks into the preset's output.
        val input = MultiTrackAudioFixture.createMultiTrackAudioMp4(
            outputPath = testDir + "two-track.mp4",
            durationSec = DURATION_SEC,
            trackFrequencies = listOf(TONE_A, TONE_B),
            channelsPerTrack = 2,
        )
        val output = testDir + "out.m4a"

        val config = AudioCompressionConfig(audioTrackIndex = 1)
        assertTrue(canUseAudioExportSession(config), "audioTrackIndex-only must stay fast-path eligible")

        val result = audioCompressor.compress(input, output, config)

        assertTrue(result.isSuccess, "track-1 compression failed: ${result.exceptionOrNull()}")
        val peak = dominantFrequency(output)
        assertTrue(
            abs(peak - TONE_B) <= PEAK_TOLERANCE_HZ,
            "expected ~$TONE_B Hz (track 1 selected), got $peak Hz",
        )
    }

    @Test
    fun audioFastPath_fastPathEligibleConfig_usesAvMutableAudioMix() = runTest {
        // Behavioural check for the *fast-path branch specifically*. We build a stereo
        // two-track MP4 so `AudioCompressionConfig()` (default STEREO) passes the upmix
        // pre-flight and lights up `canUseAudioExportSession == true`. The fast-path then
        // runs with the AVMutableAudioMix we added; without the mix, the preset would sum
        // both tones. With the mix muting track 1, the output peak must be 440 Hz.
        val input = MultiTrackAudioFixture.createMultiTrackAudioMp4(
            outputPath = testDir + "stereo-two-track.mp4",
            durationSec = DURATION_SEC,
            trackFrequencies = listOf(TONE_A, TONE_B),
            channelsPerTrack = 2,
        )
        val output = testDir + "fast_track0.m4a"

        val config = AudioCompressionConfig() // default = fast-path eligible (STEREO, no knobs)
        assertTrue(
            canUseAudioExportSession(config),
            "default config must stay fast-path eligible",
        )

        val result = audioCompressor.compress(input, output, config)
        assertTrue(result.isSuccess, "default config fast-path failed: ${result.exceptionOrNull()}")
        val peak = dominantFrequency(output)
        assertTrue(
            abs(peak - TONE_A) <= PEAK_TOLERANCE_HZ,
            "expected ~$TONE_A Hz (track 0 via audioMix muting track 1), got $peak Hz",
        )
    }

    @Test
    fun audioFastPath_singleTrackSource_stillSucceeds() = runTest {
        // The `audioTracks.size > 1` guard must not break single-track inputs. A one-track
        // source should pass through the preset without an audioMix — i.e. the same behaviour
        // this pipeline always had. The fixture builds a MONO WAV inside the MP4 container,
        // so we have to pass `channels = MONO` to avoid tripping the 1→2 upmix pre-flight.
        val input = MultiTrackAudioFixture.createMultiTrackAudioMp4(
            outputPath = testDir + "one-track.mp4",
            durationSec = DURATION_SEC,
            trackFrequencies = listOf(TONE_A),
        )
        val output = testDir + "out_single.m4a"

        val result = audioCompressor.compress(
            inputPath = input,
            outputPath = output,
            config = AudioCompressionConfig(channels = AudioChannels.MONO),
        )
        assertNotNull(result.getOrNull(), "single-track fast-path failed: ${result.exceptionOrNull()}")
    }

    private companion object {
        const val DURATION_SEC = 2
        const val TONE_A = 440
        const val TONE_B = 880
        const val PEAK_TOLERANCE_HZ = 10
    }

    // ── Helpers (Goertzel dominant-frequency finder, shared shape with MultiTrackAudioSelectionTest) ──

    private fun dominantFrequency(path: String): Int {
        val pcm = readMonoPcm(path)
        val samples = pcm.samples
        val rate = pcm.sampleRate
        val minHz = 50
        val maxHz = 2_000
        val step = 5
        var bestHz = minHz
        var bestMag = -1.0
        var hz = minHz
        while (hz <= maxHz) {
            val mag = goertzelMagnitude(samples, rate, hz.toDouble())
            if (mag > bestMag) {
                bestMag = mag
                bestHz = hz
            }
            hz += step
        }
        return bestHz
    }

    private fun goertzelMagnitude(samples: ShortArray, sampleRate: Int, freq: Double): Double {
        val n = samples.size
        val omega = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until n) {
            q0 = coeff * q1 - q2 + samples[i].toDouble()
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cos(omega)
        val imag = q2 * sin(omega)
        return real * real + imag * imag
    }

    private data class Pcm(val samples: ShortArray, val sampleRate: Int)

    @Suppress("LongMethod")
    private fun readMonoPcm(path: String): Pcm {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
            ?: error("no audio track in $path")
        val sampleRate = 44_100
        val settings: Map<Any?, *> = mapOf(
            AVFormatIDKey to kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsFloatKey to false,
            AVLinearPCMIsBigEndianKey to false,
            AVLinearPCMIsNonInterleaved to false,
            AVSampleRateKey to sampleRate,
            AVNumberOfChannelsKey to 1,
        )
        val reader = AVAssetReader(asset = asset, error = null)
        val output = AVAssetReaderTrackOutput(track = track, outputSettings = settings)
        reader.addOutput(output)
        check(reader.startReading()) { "AVAssetReader failed to start: ${reader.error}" }

        val collected = mutableListOf<ByteArray>()
        while (true) {
            val buffer = output.copyNextSampleBuffer() ?: break
            try {
                val dataBuffer = CMSampleBufferGetDataBuffer(buffer) ?: continue
                val totalSize = CMBlockBufferGetDataLength(dataBuffer).toInt()
                if (totalSize <= 0) continue
                val chunk = ByteArray(totalSize)
                chunk.usePinned { pinned ->
                    CMBlockBufferCopyDataBytes(
                        theSourceBuffer = dataBuffer,
                        offsetToData = 0u,
                        dataLength = totalSize.toULong(),
                        destination = pinned.addressOf(0),
                    )
                }
                collected.add(chunk)
            } finally {
                CFRelease(buffer)
            }
        }
        reader.cancelReading()

        val total = collected.sumOf { it.size }
        val flat = ByteArray(total)
        var offset = 0
        for (c in collected) {
            c.copyInto(flat, offset)
            offset += c.size
        }
        val samples = ShortArray(flat.size / 2)
        for (i in samples.indices) {
            val lo = flat[i * 2].toInt() and 0xFF
            val hi = flat[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return Pcm(samples, sampleRate)
    }
}
