import Kompressor
import XCTest

final class Surround51Tests: XCTestCase {
    private var testDir: URL!
    private var kompressor: (any KompressorKompressor)!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("surround51-swift-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: testDir, withIntermediateDirectories: true)
        kompressor = KompressorIosKompressorKt.createKompressor()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    func testFivePointOneSurround_producesValidOutput() async throws {
        let inputURL = testDir.appendingPathComponent("surround51.wav")
        let outputURL = testDir.appendingPathComponent("out.m4a")

        let wavData = WavFixture.generate(durationSec: 2, sampleRate: 48_000, channels: 6)
        try wavData.write(to: inputURL)

        let config = KompressorAudioCompressionConfig(
            codec: .aac,
            bitrate: 384_000,
            sampleRate: 48_000,
            channels: .fivePointOne,
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
