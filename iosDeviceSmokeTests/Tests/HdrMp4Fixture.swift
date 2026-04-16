import AVFoundation
import CoreVideo
import Foundation
import VideoToolbox

enum HdrMp4FixtureError: Error {
    case unsupportedDevice(String)
    case writerFailed(String)
}

enum HdrMp4Fixture {
    static func generate(at url: URL, width: Int = 64, height: Int = 64, frameCount: Int = 8, fps: Int = 8) throws {
        let writer = try AVAssetWriter(url: url, fileType: .mp4)
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.hevc,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_2020,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_SMPTE_ST_2084_PQ,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_2020,
            ],
            AVVideoCompressionPropertiesKey: [
                AVVideoProfileLevelKey: kVTProfileLevel_HEVC_Main10_AutoLevel as String,
            ],
        ]

        // AVAssetWriterInput.init throws an uncatchable ObjC NSException on
        // devices where the hardware encoder rejects HEVC Main10 settings —
        // even when canApply(outputSettings:) returns true. The only way to
        // survive this is an ObjC @try/@catch block.
        var input: AVAssetWriterInput!
        let objcError = ObjCExceptionCatcher.catchException {
            input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        }

        if let objcError = objcError as NSError? {
            let reason = objcError.localizedDescription
            let stack = (objcError.userInfo["NSExceptionCallStackSymbols"] as? [String])?.joined(separator: "\n") ?? "N/A"
            NSLog("[HDR10-fixture] AVAssetWriterInput threw NSException: %@", reason)
            NSLog("[HDR10-fixture] Stack trace:\n%@", stack)
            throw HdrMp4FixtureError.unsupportedDevice(
                "HEVC Main10 BT.2020/PQ AVAssetWriterInput.init failed: \(reason)"
            )
        }

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        for i in 0 ..< frameCount {
            while !input.isReadyForMoreMediaData {
                Thread.sleep(forTimeInterval: 0.01)
            }
            let time = CMTime(value: CMTimeValue(i), timescale: CMTimeScale(fps))
            guard let pool = adaptor.pixelBufferPool else {
                throw HdrMp4FixtureError.writerFailed("Pixel buffer pool unavailable")
            }
            var pixelBuffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer)
            guard let buffer = pixelBuffer else { continue }
            CVPixelBufferLockBaseAddress(buffer, [])
            let base = CVPixelBufferGetBaseAddress(buffer)!
            memset(base, 0, CVPixelBufferGetDataSize(buffer))
            CVPixelBufferUnlockBaseAddress(buffer, [])
            adaptor.append(buffer, withPresentationTime: time)
        }

        input.markAsFinished()
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()

        guard writer.status == .completed else {
            throw HdrMp4FixtureError.writerFailed(
                writer.error?.localizedDescription ?? "AVAssetWriter finished with status \(writer.status.rawValue)"
            )
        }
    }
}
