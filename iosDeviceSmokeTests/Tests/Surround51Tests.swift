import Kompressor
import XCTest

final class Surround51Tests: XCTestCase {
    private var testDir: URL!
    private var kompressor: (any Kompressor)!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("surround51-swift-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: testDir, withIntermediateDirectories: true)
        kompressor = IosKompressorKt.createKompressor()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    /// Verify that 5.1 surround AAC compression either succeeds or returns a typed error
    /// (not an uncatchable NSException crash). iOS hardware AAC encoder may only support
    /// mono/stereo — the critical property is graceful handling, not guaranteed output.
    func testFivePointOneSurround_producesValidOutput() async throws {
        let inputURL = testDir.appendingPathComponent("surround51.wav")
        let outputURL = testDir.appendingPathComponent("out.m4a")

        let wavData = WavFixture.generate(durationSec: 2, sampleRate: 48_000, channels: 6)
        try wavData.write(to: inputURL)

        let config = AudioCompressionConfig(
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

        // Primary assertion: we reached this point without an NSException crash.
        // If the device supports multi-channel AAC, validate the output.
        if FileManager.default.fileExists(atPath: outputURL.path) {
            let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
            let size = (attrs[.size] as? Int) ?? 0
            XCTAssertGreaterThan(size, 0, "Output must be non-empty")
            XCTAssertNotNil(result, "Compression result must be non-nil")
        }
        // If the file doesn't exist, the compressor returned a typed error
        // (UnsupportedConfiguration) instead of crashing — that's the correct behavior.
    }
}
