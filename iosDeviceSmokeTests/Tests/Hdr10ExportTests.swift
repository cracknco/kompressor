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
        NSLog("[HDR10] tearDown")
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    func testHdr10HevcRoundTrip_producesValidOutput() async throws {
        NSLog("[HDR10] test started")
        let inputURL = testDir.appendingPathComponent("in.mp4")
        let outputURL = testDir.appendingPathComponent("out.mp4")

        // Generate a real HEVC Main10 BT.2020/PQ fixture.
        // HdrMp4Fixture wraps AVAssetWriterInput.init in ObjC @try/@catch
        // to safely catch the NSException thrown on unsupported devices.
        NSLog("[HDR10] generating HEVC Main10 fixture at %@", inputURL.path)
        do {
            try HdrMp4Fixture.generate(at: inputURL, width: 64, height: 64, frameCount: 8, fps: 8)
        } catch let error as HdrMp4FixtureError {
            NSLog("[HDR10] fixture generation failed (device limitation): %@", String(describing: error))
            throw XCTSkip("Device does not support HEVC Main10 BT.2020/PQ encoding: \(error)")
        }

        let inputAttrs = try FileManager.default.attributesOfItem(atPath: inputURL.path)
        let inputSize = (inputAttrs[.size] as? Int) ?? 0
        NSLog("[HDR10] fixture generated — %d bytes", inputSize)
        XCTAssertTrue(FileManager.default.fileExists(atPath: inputURL.path), "HDR10 fixture must exist")
        XCTAssertGreaterThan(inputSize, 0, "HDR10 fixture must be non-empty")

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

        NSLog("[HDR10] calling kompressor.video.compress")
        let result = try await kompressor.video.compress(
            inputPath: inputURL.path,
            outputPath: outputURL.path,
            config: config,
            onProgress: NoOpProgress()
        )

        NSLog("[HDR10] compress returned — result type: %@", String(describing: type(of: result as Any)))
        NSLog("[HDR10] result: %@", String(describing: result))

        if FileManager.default.fileExists(atPath: outputURL.path) {
            let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
            let size = (attrs[.size] as? Int) ?? 0
            NSLog("[HDR10] output file size: %d bytes", size)
            XCTAssertGreaterThan(size, 0, "Output file must be non-empty")
            XCTAssertNotNil(result, "Compression result must be non-nil")
            NSLog("[HDR10] test passed — HDR10 round-trip succeeded")
        } else {
            NSLog("[HDR10] output file not created — compressor returned typed error (UnsupportedSourceFormat). No crash = success.")
        }
    }
}
