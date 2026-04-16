/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

import AVFoundation
import CoreAudioTypes
import XCTest

/// Swift port of `AudioToolboxBitrateCharacterizationTest.kt` that runs on AWS
/// Device Farm. Sweeps the grid {channel count ∈ [1, 2, 6, 8]} × {bitrate ∈
/// 32,000…1,280,000 step 32,000} at 44.1 kHz and probes each cell with a
/// single-sample-buffer round-trip through `AVAssetWriterInput` backed by
/// `kAudioFormatMPEG4AAC`. The Kotlin sibling cannot probe surround on the
/// iOS Simulator because `AVAssetWriterInput.init` throws an uncatchable
/// `NSInvalidArgumentException` for 5.1/7.1 layouts there.
///
/// Discovery tool — always passes. Emits the matrix in two forms:
/// - stdout (via `print`) so it shows in Device Farm device logs
/// - `XCTAttachment` so Device Farm captures it as a FILE artifact that the
///   GitHub Actions workflow downloads and re-uploads for human pickup.
final class AudioBitrateCharacterizationTests: XCTestCase {

    // MARK: - Sweep parameters (must match Kotlin test)

    private static let sampleRate = 44_100
    private static let bitrateStart = 32_000
    private static let bitrateEnd = 1_280_000
    private static let bitrateStep = 32_000
    private static let channelCounts = [1, 2, 6, 8]
    private static let allChannels = [1, 2, 6, 8]

    // MARK: - Lifecycle

    private var testDir: URL!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("audio-bitrate-char-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(
            at: testDir, withIntermediateDirectories: true
        )
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    // MARK: - Test

    func testSweepBitrateChannelGrid() throws {
        var results: [Pair: Bool] = [:]
        for channelCount in Self.channelCounts {
            let inputURL = try generateFixture(channelCount: channelCount)
            sweepBitrates(inputURL: inputURL, channelCount: channelCount, into: &results)
            try? FileManager.default.removeItem(at: inputURL)
        }

        let docTable = formatDocTable(results: results)
        let fullReport = formatMatrix(results: results)

        print(fullReport)

        let tableAttachment = XCTAttachment(
            data: Data(docTable.utf8),
            uniformTypeIdentifier: "net.daringfireball.markdown"
        )
        tableAttachment.name = "audio-bitrate-matrix-table.md"
        tableAttachment.lifetime = .keepAlways
        add(tableAttachment)

        let reportAttachment = XCTAttachment(
            data: Data(fullReport.utf8),
            uniformTypeIdentifier: "net.daringfireball.markdown"
        )
        reportAttachment.name = "audio-bitrate-matrix-full.md"
        reportAttachment.lifetime = .keepAlways
        add(reportAttachment)
    }

    // MARK: - Fixture

    private func generateFixture(channelCount: Int) throws -> URL {
        let wavData = WavFixture.generate(
            durationSec: 1,
            sampleRate: Self.sampleRate,
            channels: channelCount
        )
        let url = testDir.appendingPathComponent("fixture_\(channelCount)ch.wav")
        try wavData.write(to: url)
        return url
    }

    // MARK: - Sweep

    private func sweepBitrates(
        inputURL: URL,
        channelCount: Int,
        into results: inout [Pair: Bool]
    ) {
        for bitrate in stride(from: Self.bitrateStart,
                              through: Self.bitrateEnd,
                              by: Self.bitrateStep) {
            let outputURL = testDir.appendingPathComponent(
                "probe_\(channelCount)ch_\(bitrate)bps.m4a"
            )
            let accepted = probeEncoder(
                inputURL: inputURL,
                outputURL: outputURL,
                channelCount: channelCount,
                bitrate: bitrate
            )
            results[Pair(channelCount, bitrate)] = accepted
            try? FileManager.default.removeItem(at: outputURL)
        }
    }

    // MARK: - Probe

    /// Returns `true` iff the encoder accepted exactly one sample buffer at
    /// (channelCount, bitrate, 44.1 kHz). Any NSException or thrown Swift
    /// error is classified as rejection — the test intentionally probes
    /// invalid combinations and needs to survive them without crashing.
    private func probeEncoder(
        inputURL: URL,
        outputURL: URL,
        channelCount: Int,
        bitrate: Int
    ) -> Bool {
        var accepted = false
        let nsError = ObjCExceptionCatcher.catchExceptionInBlock {
            accepted = self.probeEncoderInner(
                inputURL: inputURL,
                outputURL: outputURL,
                channelCount: channelCount,
                bitrate: bitrate
            )
        }
        if nsError != nil {
            return false
        }
        return accepted
    }

    private func probeEncoderInner(
        inputURL: URL,
        outputURL: URL,
        channelCount: Int,
        bitrate: Int
    ) -> Bool {
        let asset = AVURLAsset(url: inputURL)
        guard let track = asset.tracks(withMediaType: .audio).first else {
            return false
        }
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            return false
        }
        let readerOutput = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: decodingSettings(channelCount: channelCount)
        )
        reader.add(readerOutput)

        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: outputURL, fileType: .m4a)
        } catch {
            return false
        }
        let writerInput = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: encodingSettings(
                channelCount: channelCount, bitrate: bitrate
            )
        )
        writerInput.expectsMediaDataInRealTime = false
        writer.add(writerInput)

        guard reader.startReading(), writer.startWriting() else {
            reader.cancelReading()
            writer.cancelWriting()
            return false
        }
        writer.startSession(atSourceTime: CMTime(value: 0, timescale: 1))

        guard let buffer = readerOutput.copyNextSampleBuffer() else {
            reader.cancelReading()
            writer.cancelWriting()
            return false
        }
        let didAppend = writerInput.append(buffer)
        writerInput.markAsFinished()
        reader.cancelReading()
        writer.cancelWriting()
        return didAppend
    }

    // MARK: - AV settings

    private func decodingSettings(channelCount: Int) -> [String: Any] {
        [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey: Self.sampleRate,
            AVNumberOfChannelsKey: channelCount,
        ]
    }

    private func encodingSettings(channelCount: Int, bitrate: Int) -> [String: Any] {
        [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVEncoderBitRateKey: bitrate,
            AVSampleRateKey: Self.sampleRate,
            AVNumberOfChannelsKey: channelCount,
            AVChannelLayoutKey: channelLayoutData(channelCount: channelCount),
        ]
    }

    /// Packs the 12-byte `AudioChannelLayout` prefix little-endian:
    /// `mChannelLayoutTag` = `(tag << 16) | channels` in bytes [0..3];
    /// `mChannelBitmap` and `mNumberChannelDescriptions` zeroed in bytes [4..11].
    /// Tag IDs: 100 = Mono, 101 = Stereo, 121 = MPEG_5_1_A, 128 = MPEG_7_1_C.
    private func channelLayoutData(channelCount: Int) -> Data {
        let tag: UInt32
        switch channelCount {
        case 1: tag = (100 << 16) | 1
        case 2: tag = (101 << 16) | 2
        case 6: tag = (121 << 16) | 6
        case 8: tag = (128 << 16) | 8
        default: fatalError("Unsupported channel count: \(channelCount)")
        }
        var bytes = [UInt8](repeating: 0, count: 12)
        bytes[0] = UInt8(tag & 0xFF)
        bytes[1] = UInt8((tag >> 8) & 0xFF)
        bytes[2] = UInt8((tag >> 16) & 0xFF)
        bytes[3] = UInt8((tag >> 24) & 0xFF)
        return Data(bytes)
    }

    // MARK: - Formatters

    /// Splice-ready markdown table that matches the columns between
    /// `<!-- ACCEPTANCE_MATRIX -->` markers in `docs/audio-bitrate-matrix.md`.
    private func formatDocTable(results: [Pair: Bool]) -> String {
        var out = ""
        out += "| Bitrate (bps) | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |\n"
        out += "|---------------|:----------:|:------------:|:---------:|:---------:|\n"
        for bitrate in stride(from: Self.bitrateStart,
                              through: Self.bitrateEnd,
                              by: Self.bitrateStep) {
            let cells = Self.allChannels.map { ch -> String in
                if !Self.channelCounts.contains(ch) { return "?" }
                return (results[Pair(ch, bitrate)] == true) ? "Y" : "N"
            }
            out += "| \(formatWithCommas(bitrate)) | \(cells.joined(separator: " | ")) |\n"
        }
        return out
    }

    private func formatMatrix(results: [Pair: Bool]) -> String {
        var out = ""
        out += "# AudioToolbox AAC-LC Bitrate Acceptance Matrix\n\n"
        out += "Sample rate: \(Self.sampleRate) Hz\n\n"
        out += "| Bitrate (bps) |"
        for ch in Self.channelCounts { out += " \(ch)ch |" }
        out += "\n|---------------|"
        for _ in Self.channelCounts { out += ":---:|" }
        out += "\n"
        for bitrate in stride(from: Self.bitrateStart,
                              through: Self.bitrateEnd,
                              by: Self.bitrateStep) {
            out += "| \(bitrate) |"
            for ch in Self.channelCounts {
                let mark = (results[Pair(ch, bitrate)] == true) ? " Y " : " N "
                out += "\(mark)|"
            }
            out += "\n"
        }
        out += "\nLegend: Y = encoder accepted, N = encoder rejected\n"
        return out
    }

    private func formatWithCommas(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        formatter.groupingSize = 3
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    // MARK: - Helpers

    private struct Pair: Hashable {
        let channels: Int
        let bitrate: Int
        init(_ channels: Int, _ bitrate: Int) {
            self.channels = channels
            self.bitrate = bitrate
        }
    }
}
