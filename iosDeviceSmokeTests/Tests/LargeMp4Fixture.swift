import AVFoundation
import CoreVideo
import Foundation

enum LargeMp4FixtureError: Error {
    case writerFailed(String)
}

/// Generates a large 1080p H.264 MP4 on the device, on the fly.
///
/// Target: ≥ 100 MB so the streaming pipeline (AVAssetReader → AVAssetWriter) is
/// exercised on an input that cannot fit comfortably in RAM. High-entropy BGRA frames
/// combined with a 30 Mbps bitrate ceiling and a 1-second keyframe interval defeat
/// H.264 inter-prediction well enough to produce ~200 MB for a 60s clip.
///
/// The file is NEVER committed — it is produced in a caller-supplied temp directory
/// and deleted with the test's `tearDown`.
enum LargeMp4Fixture {
    static let defaultWidth = 1920
    static let defaultHeight = 1080
    static let defaultFps = 30
    static let defaultDurationSec = 60
    static let defaultBitrate = 30_000_000

    static func generate(
        at url: URL,
        width: Int = defaultWidth,
        height: Int = defaultHeight,
        fps: Int = defaultFps,
        durationSec: Int = defaultDurationSec,
        bitrate: Int = defaultBitrate
    ) throws {
        try? FileManager.default.removeItem(at: url)

        let writer = try AVAssetWriter(url: url, fileType: .mp4)
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitrate,
                // One keyframe per second keeps the GOP short enough that random-noise
                // content produces a large bitstream — a long GOP lets H.264 collapse
                // runs of similar frames despite the noise target.
                AVVideoMaxKeyFrameIntervalKey: fps,
                AVVideoExpectedSourceFrameRateKey: fps,
            ],
        ]

        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )

        guard writer.canAdd(input) else {
            throw LargeMp4FixtureError.writerFailed("Writer refused 1080p H.264 input settings")
        }
        writer.add(input)
        guard writer.startWriting() else {
            throw LargeMp4FixtureError.writerFailed(writer.error?.localizedDescription ?? "startWriting failed")
        }
        writer.startSession(atSourceTime: .zero)

        let frameCount = fps * durationSec
        for i in 0 ..< frameCount {
            // Each iteration autoreleases transient CF objects so the fixture itself
            // doesn't bloat the process footprint before the compression measurement.
            try autoreleasepool {
                while !input.isReadyForMoreMediaData {
                    Thread.sleep(forTimeInterval: 0.002)
                }
                let time = CMTime(value: CMTimeValue(i), timescale: CMTimeScale(fps))
                guard let pool = adaptor.pixelBufferPool else {
                    throw LargeMp4FixtureError.writerFailed("Pixel buffer pool unavailable")
                }
                var pixelBuffer: CVPixelBuffer?
                CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer)
                guard let buffer = pixelBuffer else {
                    throw LargeMp4FixtureError.writerFailed("Pixel buffer allocation failed at frame \(i)")
                }
                CVPixelBufferLockBaseAddress(buffer, [])
                let base = CVPixelBufferGetBaseAddress(buffer)!
                let bytes = CVPixelBufferGetDataSize(buffer)
                // High-entropy content so the H.264 encoder has little redundancy to collapse.
                // `arc4random_buf` is vDSO-fast on iOS and comfortably keeps up with the encoder.
                arc4random_buf(base, bytes)
                CVPixelBufferUnlockBaseAddress(buffer, [])
                guard adaptor.append(buffer, withPresentationTime: time) else {
                    throw LargeMp4FixtureError.writerFailed(
                        writer.error?.localizedDescription ?? "appendPixelBuffer failed at frame \(i)"
                    )
                }
            }
        }

        input.markAsFinished()
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()

        guard writer.status == .completed else {
            throw LargeMp4FixtureError.writerFailed(
                writer.error?.localizedDescription ?? "AVAssetWriter finished with status \(writer.status.rawValue)"
            )
        }
    }
}
