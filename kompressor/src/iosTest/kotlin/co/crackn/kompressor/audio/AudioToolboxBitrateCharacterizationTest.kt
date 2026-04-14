@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVChannelLayoutKey
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVFileTypeAppleM4A
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Characterization test that empirically discovers which bitrate / channel-count combinations
 * Apple's AudioToolbox AAC-LC encoder accepts via [AVAssetWriterInput]. Sweeps the grid
 * {channels: 1, 2} x {bitrate: 32k-1280k, step 32k} at 44.1 kHz on the iOS simulator.
 * Surround (6, 8) is gated to hardware runs — see [CHANNEL_COUNTS] for why.
 *
 * This is a **discovery tool**, not a regression gate -- it always passes. Results are printed
 * to stdout and written to a UUID-suffixed file under `NSTemporaryDirectory()` (the path is
 * logged) for manual review and incorporation into `docs/audio-bitrate-matrix.md`.
 */
class AudioToolboxBitrateCharacterizationTest {

    private lateinit var testDir: String

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-bitrate-char-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun sweepBitrateChannelGrid() {
        val results = mutableMapOf<Pair<Int, Int>, Boolean>()
        for (channelCount in CHANNEL_COUNTS) {
            val inputPath = generateFixture(channelCount)
            sweepBitratesForChannel(inputPath, channelCount, results)
            NSFileManager.defaultManager.removeItemAtPath(inputPath, null)
        }
        val markdown = formatMatrix(results)
        println(markdown)
        // UUID-suffixed so parallel runs don't collide, and written outside `testDir` so the
        // file survives `tearDown()` for manual inspection.
        val matrixPath = NSTemporaryDirectory() + "audio-bitrate-matrix-${NSUUID().UUIDString}.md"
        writeBytes(matrixPath, markdown.encodeToByteArray())
        println("Matrix written to: $matrixPath")
    }

    private fun generateFixture(channelCount: Int): String {
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE,
            channels = channelCount,
        )
        val path = testDir + "fixture_${channelCount}ch.wav"
        writeBytes(path, wavBytes)
        return path
    }

    private fun sweepBitratesForChannel(
        inputPath: String,
        channelCount: Int,
        results: MutableMap<Pair<Int, Int>, Boolean>,
    ) {
        for (bitrate in BITRATE_START..BITRATE_END step BITRATE_STEP) {
            val outputPath = testDir + "probe_${channelCount}ch_${bitrate}bps.m4a"
            results[channelCount to bitrate] = probeEncoder(inputPath, outputPath, channelCount, bitrate)
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun probeEncoder(
        inputPath: String,
        outputPath: String,
        channelCount: Int,
        bitrate: Int,
    ): Boolean = try {
        val (reader, readerOutput) = createReader(inputPath, channelCount) ?: return false
        val (writer, writerInput) = createWriter(outputPath, channelCount, bitrate) ?: return false
        tryAppendOneSample(reader, readerOutput, writer, writerInput)
    } catch (_: Throwable) {
        false
    }

    @Suppress("UNCHECKED_CAST")
    private fun createReader(
        inputPath: String,
        channelCount: Int,
    ): Pair<AVAssetReader, AVAssetReaderTrackOutput>? {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
            ?: return null
        val reader = AVAssetReader(asset = asset, error = null)
        val output = AVAssetReaderTrackOutput(
            track = track,
            outputSettings = decodingSettings(channelCount),
        )
        reader.addOutput(output)
        return reader to output
    }

    private fun createWriter(
        outputPath: String,
        channelCount: Int,
        bitrate: Int,
    ): Pair<AVAssetWriter, AVAssetWriterInput>? {
        val writer = AVAssetWriter.assetWriterWithURL(
            NSURL.fileURLWithPath(outputPath),
            fileType = AVFileTypeAppleM4A,
            error = null,
        ) ?: return null
        val input = AVAssetWriterInput.assetWriterInputWithMediaType(
            mediaType = AVMediaTypeAudio,
            outputSettings = encodingSettings(channelCount, bitrate),
        )
        input.expectsMediaDataInRealTime = false
        writer.addInput(input)
        return writer to input
    }

    private fun tryAppendOneSample(
        reader: AVAssetReader,
        readerOutput: AVAssetReaderTrackOutput,
        writer: AVAssetWriter,
        writerInput: AVAssetWriterInput,
    ): Boolean {
        if (!reader.startReading() || !writer.startWriting()) {
            reader.cancelReading()
            writer.cancelWriting()
            return false
        }
        writer.startSessionAtSourceTime(CMTimeMake(value = 0, timescale = 1))
        val buffer = readerOutput.copyNextSampleBuffer()
        if (buffer == null) {
            reader.cancelReading()
            writer.cancelWriting()
            return false
        }
        val accepted = writerInput.appendSampleBuffer(buffer)
        CFRelease(buffer)
        writerInput.markAsFinished()
        reader.cancelReading()
        writer.cancelWriting()
        return accepted
    }

    private fun decodingSettings(channelCount: Int): Map<Any?, *> = mapOf(
        AVFormatIDKey to kAudioFormatLinearPCM,
        AVLinearPCMBitDepthKey to PCM_BIT_DEPTH,
        AVLinearPCMIsFloatKey to false,
        AVLinearPCMIsBigEndianKey to false,
        AVLinearPCMIsNonInterleaved to false,
        AVSampleRateKey to SAMPLE_RATE,
        AVNumberOfChannelsKey to channelCount,
    )

    private fun encodingSettings(channelCount: Int, bitrate: Int): Map<Any?, *> = buildMap {
        put(AVFormatIDKey, kAudioFormatMPEG4AAC)
        put(AVEncoderBitRateKey, bitrate)
        put(AVSampleRateKey, SAMPLE_RATE)
        put(AVNumberOfChannelsKey, channelCount)
        put(AVChannelLayoutKey, channelLayoutData(channelCount))
    }

    @Suppress("MagicNumber")
    private fun channelLayoutData(channelCount: Int): NSData {
        // Only mono/stereo are swept on the simulator — see [CHANNEL_COUNTS]. Extend this
        // `when` (5.1 → tag 121, 7.1 → tag 128) when re-enabling the surround sweep on
        // hardware.
        val tag: UInt = when (channelCount) {
            1 -> (100u shl 16) or 1u  // kAudioChannelLayoutTag_Mono
            2 -> (101u shl 16) or 2u  // kAudioChannelLayoutTag_Stereo
            else -> error("Unsupported channel count: $channelCount")
        }
        val bytes = ByteArray(AUDIO_CHANNEL_LAYOUT_SIZE)
        bytes[0] = (tag and 0xFFu).toByte()
        bytes[1] = ((tag shr 8) and 0xFFu).toByte()
        bytes[2] = ((tag shr 16) and 0xFFu).toByte()
        bytes[3] = ((tag shr 24) and 0xFFu).toByte()
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    }

    private fun formatMatrix(results: Map<Pair<Int, Int>, Boolean>): String = buildString {
        appendLine("# AudioToolbox AAC-LC Bitrate Acceptance Matrix")
        appendLine()
        appendLine("Sample rate: $SAMPLE_RATE Hz")
        appendLine()
        append("| Bitrate (bps) |")
        for (ch in CHANNEL_COUNTS) append(" ${ch}ch |")
        appendLine()
        append("|---------------|")
        for (ignored in CHANNEL_COUNTS) append(":---:|")
        appendLine()
        for (bitrate in BITRATE_START..BITRATE_END step BITRATE_STEP) {
            append("| $bitrate |")
            for (ch in CHANNEL_COUNTS) {
                val mark = if (results[ch to bitrate] == true) " Y " else " N "
                append("$mark|")
            }
            appendLine()
        }
        appendLine()
        appendLine("Legend: Y = encoder accepted, N = encoder rejected")
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val PCM_BIT_DEPTH = 16
        const val BITRATE_START = 32_000
        const val BITRATE_END = 1_280_000
        const val BITRATE_STEP = 32_000
        const val AUDIO_CHANNEL_LAYOUT_SIZE = 12
        // Surround (6, 8) excluded: iOS simulator's AAC encoder rejects surround channel
        // layouts with an uncatchable NSInvalidArgumentException. Run on a real device (A10+)
        // to probe surround caps — add 6 and 8 back to this list when running on hardware.
        val CHANNEL_COUNTS = listOf(1, 2)
    }
}
