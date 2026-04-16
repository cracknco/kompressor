import AVFoundation
import Foundation
import Kompressor
import XCTest

/// Device-only stress test for the iOS streaming pipeline on a >100 MB input.
///
/// Covers the gap left by [CRA-11]: Phase 3 had no iOS test exercising inputs large enough
/// to OOM a Mac simulator, so the claim "the pipeline streams instead of loading everything
/// into RAM" was unverified. This test generates a ~200 MB 1080p 60s H.264 fixture on the
/// device, runs it through `IosVideoCompressor.compress`, and asserts:
///
/// * peak `phys_footprint` stays ≤ 300 MB (streaming, not full load),
/// * the output is re-probable (AVURLAsset + video track + positive duration),
/// * `onProgress` fires at least five times (progress is reported during the stream).
///
/// Runs only in the device-smoke XCTest bundle, which `ios-device-smoke.yml` uploads to
/// AWS Device Farm. Bundle-level placement is this repo's "`deviceOnly`" tag — see
/// `docs/ios-device-ci.md`.
final class IosLargeInputStreamingTest: XCTestCase {
    private var testDir: URL!
    private var kompressor: (any Kompressor)!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("large-streaming-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: testDir, withIntermediateDirectories: true)
        kompressor = IosKompressorKt.createKompressor()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    func testLargeVideoInputStreamsWithinMemoryBudget() async throws {
        let inputURL = testDir.appendingPathComponent("large-in.mp4")
        let outputURL = testDir.appendingPathComponent("out.mp4")

        // Fixture generation is wrapped in its own autoreleasepool so any transient
        // pixel-buffer-pool allocations drain before we start measuring compression memory.
        try autoreleasepool {
            try LargeMp4Fixture.generate(at: inputURL)
        }

        let inputSize = try fileSize(at: inputURL)
        NSLog("[LargeStreaming] fixture size: %.1f MB", Double(inputSize) / 1_048_576.0)
        XCTAssertGreaterThan(
            inputSize,
            LargeInputMinBytes,
            "Fixture must exceed 100 MB to exercise the streaming path"
        )

        let config = VideoCompressionConfig(
            codec: .h264,
            maxResolution: MaxResolution.Companion.shared.HD_720,
            videoBitrate: 2_000_000,
            audioBitrate: 64_000,
            audioCodec: .aac,
            maxFrameRate: 30,
            keyFrameInterval: 2,
            dynamicRange: .sdr
        )

        let progress = CountingProgress()
        let sampler = PeakMemorySampler(intervalMs: 50)

        let baselineBytes = MemoryProbe.physFootprintBytes()
        NSLog("[LargeStreaming] baseline phys_footprint: %.1f MB", Double(baselineBytes) / 1_048_576.0)

        sampler.start()
        let result: Any?
        do {
            result = try await kompressor.video.compress(
                inputPath: inputURL.path,
                outputPath: outputURL.path,
                config: config,
                onProgress: progress
            )
        } catch {
            _ = sampler.stop()
            throw error
        }
        let peakBytes = sampler.stop()

        NSLog(
            "[LargeStreaming] peak phys_footprint: %.1f MB (delta: %.1f MB)",
            Double(peakBytes) / 1_048_576.0,
            Double(Int64(peakBytes) - Int64(baselineBytes)) / 1_048_576.0
        )

        XCTAssertLessThanOrEqual(
            peakBytes,
            PeakMemoryBudgetBytes,
            "Peak phys_footprint \(peakBytes) B exceeds 300 MB budget — pipeline is likely loading the full input into RAM"
        )

        XCTAssertGreaterThanOrEqual(
            progress.count,
            MinProgressUpdates,
            "onProgress fired only \(progress.count) time(s); streaming should produce at least \(MinProgressUpdates) updates"
        )

        try assertOutputIsReprobable(outputURL, kompressionResult: result)
    }

    // MARK: - Helpers

    private func fileSize(at url: URL) throws -> Int64 {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func assertOutputIsReprobable(_ url: URL, kompressionResult: Any?) throws {
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "Output file must exist")
        let outputSize = try fileSize(at: url)
        XCTAssertGreaterThan(outputSize, 0, "Output file must be non-empty")
        XCTAssertNotNil(kompressionResult, "CompressionResult must be returned")

        let asset = AVURLAsset(url: url)
        let videoTracks = asset.tracks(withMediaType: .video)
        XCTAssertFalse(videoTracks.isEmpty, "Output must have a video track")
        let durationSec = CMTimeGetSeconds(asset.duration)
        XCTAssertGreaterThan(durationSec, 0.0, "Output duration must be > 0 s")
    }

    // MARK: - Budgets

    /// Phase 3 DoD: process-level peak ≤ 300 MB during compression of the large fixture.
    private let PeakMemoryBudgetBytes: UInt64 = 300 * 1_048_576

    /// Minimum input size that makes the "streaming, not full-load" assertion meaningful.
    private let LargeInputMinBytes: Int64 = 100 * 1_048_576

    /// Phase 3 DoD: onProgress must fire at least 5 times over a multi-minute stream.
    private let MinProgressUpdates = 5
}
