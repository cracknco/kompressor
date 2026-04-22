/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.testutil.MultiTrackAudioFixture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
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
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * iOS device-level tests for `AudioCompressionConfig.audioTrackIndex`. The fixture builds a
 * two-track MP4 (440 Hz / 880 Hz) and we confirm the selected track's tone survives compression
 * and shows up as the dominant frequency in the output.
 */
@OptIn(ExperimentalForeignApi::class)
class MultiTrackAudioSelectionTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()
    private val kompressor = IosKompressor(NoOpLogger)

    private companion object {
        const val DURATION_SEC = 2
        const val TONE_A = 440
        const val TONE_B = 880
        const val PEAK_TOLERANCE_HZ = 10
    }

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-multitrack-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressAudio_selectsTrack0() = runTest {
        val input = buildTwoTrackFixture()
        val output = testDir + "out0.m4a"
        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(channels = AudioChannels.MONO, audioTrackIndex = 0),
        )
        assertTrue(result.isSuccess, "result=$result")
        val peak = dominantFrequency(output)
        assertTrue(abs(peak - TONE_A) <= PEAK_TOLERANCE_HZ, "expected ~$TONE_A Hz, got $peak")
    }

    @Test
    fun compressAudio_selectsTrack1() = runTest {
        val input = buildTwoTrackFixture()
        val output = testDir + "out1.m4a"
        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(channels = AudioChannels.MONO, audioTrackIndex = 1),
        )
        assertTrue(result.isSuccess, "result=$result")
        val peak = dominantFrequency(output)
        assertTrue(abs(peak - TONE_B) <= PEAK_TOLERANCE_HZ, "expected ~$TONE_B Hz, got $peak")
    }

    @Test
    fun compressAudio_outOfBoundsIndex_returnsUnsupportedSourceFormat() = runTest {
        val input = buildTwoTrackFixture()
        val output = testDir + "out_oob.m4a"
        val result = compressor.compress(
            MediaSource.Local.FilePath(input),
            MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(channels = AudioChannels.MONO, audioTrackIndex = 5),
        )
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.UnsupportedSourceFormat,
            "expected UnsupportedSourceFormat, got $err",
        )
    }

    @Test
    fun audioCompressionConfig_rejectsNegativeIndex() {
        assertFailsWith<IllegalArgumentException> {
            AudioCompressionConfig(audioTrackIndex = -1)
        }
    }

    @Test
    fun probe_reportsAudioTrackCountForMultiTrackInput() = runTest {
        val input = buildTwoTrackFixture()
        val info = kompressor.probe(input).getOrThrow()
        assertEquals(2, info.audioTrackCount)
    }

    @Test
    fun probe_reportsSingleAudioTrackForSingleTrackInput() = runTest {
        val input = MultiTrackAudioFixture.createMultiTrackAudioMp4(
            outputPath = testDir + "single-track.mp4",
            durationSec = 1,
            trackFrequencies = listOf(TONE_A),
        )
        val info = kompressor.probe(input).getOrThrow()
        assertEquals(1, info.audioTrackCount)
    }

    @Test
    fun probe_reportsZeroAudioTracksForVideoOnlyInput() = runTest {
        val input = testDir + "video-only.mp4"
        co.crackn.kompressor.testutil.Mp4Generator.generateMp4(outputPath = input, frameCount = 10)
        val info = kompressor.probe(input).getOrThrow()
        assertEquals(0, info.audioTrackCount)
    }

    private fun buildTwoTrackFixture(): String = MultiTrackAudioFixture.createMultiTrackAudioMp4(
        outputPath = testDir + "two-track.mp4",
        durationSec = DURATION_SEC,
        trackFrequencies = listOf(TONE_A, TONE_B),
    )

    /**
     * Decode the output audio file back to mono PCM via `AVAssetReader` (PCM output settings)
     * and report the dominant frequency via a Goertzel sweep over `[50, 2000] Hz` at 5 Hz
     * resolution.
     */
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

    /**
     * Decode the first audio track of [path] via `AVAssetReader` into interleaved 16-bit PCM.
     * Averages channels down to mono when the track is stereo.
     */
    @Suppress("LongMethod")
    private fun readMonoPcm(path: String): Pcm {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
            ?: error("no audio track in $path")
        // Ask for interleaved signed-16 LE PCM at 44.1 kHz mono. AVAssetReaderTrackOutput will
        // resample/downmix as needed — the exact output sample rate is what we query below.
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
                val totalSize = platform.CoreMedia.CMBlockBufferGetDataLength(dataBuffer).toInt()
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
