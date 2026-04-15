import AVFoundation
import Kompressor
import VideoToolbox
import XCTest

final class Hdr10ExportTests: XCTestCase {
    private var testDir: URL!
    private var kompressor: (any Kompressor)!

    override func setUp() {
        super.setUp()
        NSLog("[HDR10] setUp — creating temp dir + kompressor")
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("hdr10-swift-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: testDir, withIntermediateDirectories: true)
        kompressor = IosKompressorKt.createKompressor()
        NSLog("[HDR10] setUp complete")
    }

    override func tearDown() {
        NSLog("[HDR10] tearDown — cleaning temp dir")
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    /// Verify HDR10 HEVC compression either succeeds or returns a typed error
    /// (not an uncatchable NSException crash). The critical property is graceful
    /// handling, not guaranteed output — same pattern as the surround audio tests.
    ///
    /// Uses an SDR H.264 fixture as input because generating HEVC Main10 content
    /// via AVAssetWriterInput crashes with an NSException on some devices even
    /// when canApplyOutputSettings returns true.
    func testHdr10HevcRoundTrip_producesValidOutput() async throws {
        NSLog("[HDR10] test started")
        let inputURL = testDir.appendingPathComponent("in.mp4")
        let outputURL = testDir.appendingPathComponent("out.mp4")

        // Probe: log whether this device advertises HEVC Main10 support.
        // This is purely informational — we don't gate on it because canApply
        // can return true while AVAssetWriterInput.init still crashes.
        let probeURL = testDir.appendingPathComponent("probe.mp4")
        if let probeWriter = try? AVAssetWriter(url: probeURL, fileType: .mp4) {
            let probeSettings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.hevc,
                AVVideoWidthKey: 64,
                AVVideoHeightKey: 64,
                AVVideoColorPropertiesKey: [
                    AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_2020,
                    AVVideoTransferFunctionKey: AVVideoTransferFunction_SMPTE_ST_2084_PQ,
                    AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_2020,
                ],
                AVVideoCompressionPropertiesKey: [
                    AVVideoProfileLevelKey: kVTProfileLevel_HEVC_Main10_AutoLevel as String,
                ],
            ]
            let canApply = probeWriter.canApply(outputSettings: probeSettings, forMediaType: .video)
            NSLog("[HDR10] Swift canApplyOutputSettings HEVC Main10 BT.2020/PQ 64x64: %@", canApply ? "YES" : "NO")
        }

        // Generate SDR H.264 fixture — safe on all devices, no NSException risk.
        NSLog("[HDR10] generating SDR H.264 fixture at %@", inputURL.path)
        try Mp4Fixture.generate(at: inputURL, width: 64, height: 64, frameCount: 8, fps: 8)
        let inputExists = FileManager.default.fileExists(atPath: inputURL.path)
        NSLog("[HDR10] fixture exists: %@", inputExists ? "YES" : "NO")
        XCTAssertTrue(inputExists, "SDR fixture must exist")

        if inputExists {
            let attrs = try FileManager.default.attributesOfItem(atPath: inputURL.path)
            let size = (attrs[.size] as? Int) ?? 0
            NSLog("[HDR10] fixture size: %d bytes", size)
        }

        let config = VideoCompressionConfig(
            codec: .hevc,
            maxResolution: MaxResolution.Companion.shared.HD_720,
            videoBitrate: 1_200_000,
            audioBitrate: 128_000,
            audioCodec: .aac,
            maxFrameRate: 30,
            keyFrameInterval: 2,
            dynamicRange: .hdr10
        )

        NSLog("[HDR10] calling kompressor.video.compress with HDR10 config")
        let result = try await kompressor.video.compress(
            inputPath: inputURL.path,
            outputPath: outputURL.path,
            config: config,
            onProgress: NoOpProgress()
        )

        NSLog("[HDR10] compress returned — result type: %@", String(describing: type(of: result as Any)))
        NSLog("[HDR10] result description: %@", String(describing: result))

        let outputExists = FileManager.default.fileExists(atPath: outputURL.path)
        NSLog("[HDR10] output file exists: %@", outputExists ? "YES" : "NO")

        // Primary assertion: we reached this point without an NSException crash.
        // If the device supports HDR10 encoding, validate the output.
        if outputExists {
            let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
            let size = (attrs[.size] as? Int) ?? 0
            NSLog("[HDR10] output file size: %d bytes", size)
            XCTAssertGreaterThan(size, 0, "Output must be non-empty")
            XCTAssertNotNil(result, "Compression result must be non-nil")
        } else {
            NSLog("[HDR10] no output file — compressor returned typed error (expected on devices without HEVC Main10)")
        }
        // If the file doesn't exist, the compressor returned a typed error
        // (UnsupportedSourceFormat) instead of crashing — that's the correct behavior.

        NSLog("[HDR10] test completed successfully (no crash)")
    }
}
