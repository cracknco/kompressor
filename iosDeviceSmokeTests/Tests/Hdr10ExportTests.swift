import AVFoundation
import Kompressor
import VideoToolbox
import XCTest

final class Hdr10ExportTests: XCTestCase {
    private var testDir: URL!
    private var kompressor: (any Kompressor)!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("hdr10-swift-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: testDir, withIntermediateDirectories: true)
        kompressor = IosKompressorKt.createKompressor()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    func testHdr10HevcRoundTrip_producesValidOutput() async throws {
        let inputURL = testDir.appendingPathComponent("in.mp4")
        let outputURL = testDir.appendingPathComponent("out.mp4")

        // Diagnostic: probe HDR10 capability from Swift directly
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
            NSLog("[HDR10-diag] canApplyOutputSettings HEVC Main10 BT.2020/PQ: %d", canApply ? 1 : 0)
        }

        try HdrMp4Fixture.generate(at: inputURL, width: 64, height: 64, frameCount: 8, fps: 8)
        XCTAssertTrue(FileManager.default.fileExists(atPath: inputURL.path), "HDR10 fixture must exist")

        let inputAttrs = try FileManager.default.attributesOfItem(atPath: inputURL.path)
        let inputSize = (inputAttrs[.size] as? Int) ?? 0
        NSLog("[HDR10-diag] fixture size: %d bytes", inputSize)

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

        let result = try await kompressor.video.compress(
            inputPath: inputURL.path,
            outputPath: outputURL.path,
            config: config,
            onProgress: NoOpProgress()
        )

        NSLog("[HDR10-diag] result type: %@", String(describing: type(of: result as Any)))
        NSLog("[HDR10-diag] result description: %@", String(describing: result))
        NSLog("[HDR10-diag] output exists: %d", FileManager.default.fileExists(atPath: outputURL.path) ? 1 : 0)

        XCTAssertTrue(FileManager.default.fileExists(atPath: outputURL.path), "Output file must exist")
        if FileManager.default.fileExists(atPath: outputURL.path) {
            let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
            let size = (attrs[.size] as? Int) ?? 0
            XCTAssertGreaterThan(size, 0, "Output file must be non-empty")
        }
        XCTAssertNotNil(result, "Compression result must be non-nil")
    }
}
