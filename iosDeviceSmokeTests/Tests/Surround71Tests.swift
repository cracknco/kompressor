import Kompressor
import XCTest

final class Surround71Tests: XCTestCase {
    private var testDir: URL!
    private var kompressor: (any Kompressor)!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("surround71-swift-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: testDir, withIntermediateDirectories: true)
        kompressor = IosKompressorKt.createKompressor()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    func testSevenPointOneSurround_producesValidOutput() async throws {
        let inputURL = testDir.appendingPathComponent("surround71.wav")
        let outputURL = testDir.appendingPathComponent("out.m4a")

        let wavData = WavFixture.generate(durationSec: 2, sampleRate: 48_000, channels: 8)
        try wavData.write(to: inputURL)

        let config = AudioCompressionConfig(
            codec: .aac,
            bitrate: 512_000,
            sampleRate: 48_000,
            channels: .sevenPointOne,
            audioTrackIndex: 0
        )

        let result = try await kompressor.audio.compress(
            inputPath: inputURL.path,
            outputPath: outputURL.path,
            config: config,
            onProgress: NoOpProgress()
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: outputURL.path), "Output file must exist")
        let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
        let size = (attrs[.size] as? Int) ?? 0
        XCTAssertGreaterThan(size, 0, "Output must be non-empty")
        XCTAssertNotNil(result, "Compression result must be non-nil")
    }
}
