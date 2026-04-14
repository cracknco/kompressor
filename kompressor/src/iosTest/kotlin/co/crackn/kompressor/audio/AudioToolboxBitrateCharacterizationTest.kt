@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Characterization test that empirically discovers which bitrate / channel-count combinations
 * Apple's AudioToolbox AAC-LC encoder accepts via [AVAssetWriterInput]. Sweeps the grid
 * {channels: 1, 2, 6, 8} x {bitrate: 32k-512k, step 32k} at 44.1 kHz.
 *
 * This is a **discovery tool**, not a regression gate -- it always passes. Results are printed
 * to stdout and written to `NSTemporaryDirectory()/audio-bitrate-matrix.md` for manual review
 * and incorporation into `docs/audio-bitrate-matrix.md`.
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
        writeBytes(
            NSTemporaryDirectory() + "audio-bitrate-matrix.md",
            markdown.encodeToByteArray(),
        )
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

    private fun encodingSettings(channelCount: Int, bitrate: Int): Map<Any?, *> = mapOf(
        AVFormatIDKey to kAudioFormatMPEG4AAC,
        AVEncoderBitRateKey to bitrate,
        AVSampleRateKey to SAMPLE_RATE,
        AVNumberOfChannelsKey to channelCount,
    )

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
        const val BITRATE_END = 512_000
        const val BITRATE_STEP = 32_000
        val CHANNEL_COUNTS = listOf(1, 2, 6, 8)
    }
}
