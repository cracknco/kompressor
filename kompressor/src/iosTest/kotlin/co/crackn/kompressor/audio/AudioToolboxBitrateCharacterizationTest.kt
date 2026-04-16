/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readBytes
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
import platform.Foundation.NSProcessInfo
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
 * {channels × bitrate: 32k–1280k, step 32k} at 44.1 kHz.
 *
 * This is a **discovery tool**, not a regression gate — it always passes. Results are printed
 * to stdout. When the `KOMPRESSOR_DOCS_DIR` environment variable is set, the matrix is also
 * written to `$KOMPRESSOR_DOCS_DIR/audio-bitrate-matrix.md`; otherwise it falls back to a
 * UUID-suffixed file in `NSTemporaryDirectory()`.
 *
 * **Channel scope — simulator only:** this Kotlin test ships in the Kotlin/Native `iosTest`
 * target, which runs on the iOS Simulator in CI (see [CHANNEL_COUNTS]). Simulator's AAC encoder
 * rejects 5.1/7.1 layouts with an uncatchable `NSInvalidArgumentException`, so surround columns
 * are gated to `[1, 2]`. This is a cheap mono/stereo sanity guardrail — it cannot fill 6ch/8ch
 * cells.
 *
 * **Surround discovery — Swift sibling on Device Farm:** the full 4×40 sweep (including 6ch/8ch)
 * runs as an XCTest Swift port in
 * `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift`, triggered via the
 * `ios-audio-characterization.yml` workflow_dispatch workflow on A15+ hardware. Paste the
 * `audio-bitrate-matrix-*` artifact into `docs/audio-bitrate-matrix.md` between the
 * `<!-- ACCEPTANCE_MATRIX -->` markers.
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
        writeMatrixFile(markdown, results)
    }

    private fun writeMatrixFile(
        markdown: String,
        results: Map<Pair<Int, Int>, Boolean>,
    ) {
        val docsDir = NSProcessInfo.processInfo.environment[DOCS_DIR_ENV_KEY] as? String
        if (docsDir != null) {
            val repoPath = "$docsDir/audio-bitrate-matrix.md"
            spliceAcceptanceMatrix(repoPath, formatDocTable(results))
            println("Matrix spliced into repo: $repoPath")
        }
        val tempPath = NSTemporaryDirectory() + "audio-bitrate-matrix-${NSUUID().UUIDString}.md"
        writeBytes(tempPath, markdown.encodeToByteArray())
        println("Matrix written to temp: $tempPath")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun spliceAcceptanceMatrix(docPath: String, table: String) {
        val existing = try {
            readBytes(docPath).decodeToString()
        } catch (_: Throwable) {
            return
        }
        val start = existing.indexOf(SPLICE_START_MARKER)
        val end = existing.indexOf(SPLICE_END_MARKER)
        if (start < 0 || end < 0 || end <= start) return
        val updated = existing.substring(0, start + SPLICE_START_MARKER.length) +
            "\n" + table.trimEnd() + "\n" +
            existing.substring(end)
        writeBytes(docPath, updated.encodeToByteArray())
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
        val tag: UInt = when (channelCount) {
            1 -> (100u shl 16) or 1u  // kAudioChannelLayoutTag_Mono
            2 -> (101u shl 16) or 2u  // kAudioChannelLayoutTag_Stereo
            6 -> (121u shl 16) or 6u  // kAudioChannelLayoutTag_MPEG_5_1_A
            8 -> (128u shl 16) or 8u  // kAudioChannelLayoutTag_MPEG_7_1_C
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

    private fun formatDocTable(results: Map<Pair<Int, Int>, Boolean>): String = buildString {
        appendLine("| Bitrate (bps) | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |")
        appendLine("|---------------|:----------:|:------------:|:---------:|:---------:|")
        for (bitrate in BITRATE_START..BITRATE_END step BITRATE_STEP) {
            val cols = ALL_CHANNELS.joinToString(" | ") { ch ->
                when {
                    ch !in CHANNEL_COUNTS -> "?"
                    results[ch to bitrate] == true -> "Y"
                    else -> "N"
                }
            }
            appendLine("| ${formatWithCommas(bitrate)} | $cols |")
        }
    }

    @Suppress("MagicNumber")
    private fun formatWithCommas(value: Int): String {
        val s = value.toString()
        return buildString {
            s.reversed().forEachIndexed { i, c ->
                if (i > 0 && i % 3 == 0) append(',')
                append(c)
            }
        }.reversed()
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
        const val DOCS_DIR_ENV_KEY = "KOMPRESSOR_DOCS_DIR"
        const val SPLICE_START_MARKER = "<!-- ACCEPTANCE_MATRIX -->"
        const val SPLICE_END_MARKER = "<!-- /ACCEPTANCE_MATRIX -->"
        val ALL_CHANNELS = listOf(1, 2, 6, 8)
        // Surround (6, 8) excluded: iOS simulator's AAC encoder rejects surround channel
        // layouts with an uncatchable NSInvalidArgumentException. Surround discovery runs in
        // the Swift sibling (AudioBitrateCharacterizationTests.swift) on Device Farm hardware.
        val CHANNEL_COUNTS = listOf(1, 2)
    }
}
