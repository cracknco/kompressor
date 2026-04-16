import Kompressor
import XCTest

final class SanityTests: XCTestCase {
    func testKompressorCanBeCreated() {
        let kompressor = IosKompressorKt.createKompressor()
        XCTAssertNotNil(kompressor)
    }

    func testMp4FixtureCanBeGenerated() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sanity-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let url = dir.appendingPathComponent("test.mp4")
        try Mp4Fixture.generate(at: url, width: 32, height: 32, frameCount: 4, fps: 4)

        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attrs[.size] as? Int) ?? 0
        XCTAssertGreaterThan(size, 0, "MP4 fixture must be non-empty")
    }

    func testSimpleH264Compression() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sanity-h264-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let inputURL = dir.appendingPathComponent("in.mp4")
        let outputURL = dir.appendingPathComponent("out.mp4")
        try Mp4Fixture.generate(at: inputURL, width: 32, height: 32, frameCount: 4, fps: 4)

        let kompressor = IosKompressorKt.createKompressor()
        let config = VideoCompressionConfig(
            codec: .h264,
            maxResolution: MaxResolution.Companion.shared.HD_720,
            videoBitrate: 500_000,
            audioBitrate: 64_000,
            audioCodec: .aac,
            maxFrameRate: 30,
            keyFrameInterval: 2,
            dynamicRange: .sdr
        )

        let result = try await kompressor.video.compress(
            inputPath: inputURL.path,
            outputPath: outputURL.path,
            config: config,
            onProgress: NoOpProgress()
        )

        XCTAssertNotNil(result)
        XCTAssertTrue(FileManager.default.fileExists(atPath: outputURL.path))
    }
}
