import Foundation

enum WavFixture {
    static func generate(durationSec: Int, sampleRate: Int, channels: Int) -> Data {
        let numSamples = sampleRate * durationSec * channels
        let bitsPerSample: Int = 16
        let bytesPerSample = bitsPerSample / 8
        let dataSize = numSamples * bytesPerSample
        let fileSize = 36 + dataSize

        var data = Data()
        data.append(contentsOf: "RIFF".utf8)
        data.append(littleEndian: UInt32(fileSize))
        data.append(contentsOf: "WAVE".utf8)
        data.append(contentsOf: "fmt ".utf8)
        data.append(littleEndian: UInt32(16))
        data.append(littleEndian: UInt16(1))
        data.append(littleEndian: UInt16(channels))
        data.append(littleEndian: UInt32(sampleRate))
        data.append(littleEndian: UInt32(sampleRate * channels * bytesPerSample))
        data.append(littleEndian: UInt16(channels * bytesPerSample))
        data.append(littleEndian: UInt16(bitsPerSample))
        data.append(contentsOf: "data".utf8)
        data.append(littleEndian: UInt32(dataSize))

        let silence = Data(count: dataSize)
        data.append(silence)
        return data
    }
}

private extension Data {
    mutating func append(littleEndian value: UInt16) {
        var le = value.littleEndian
        append(Data(bytes: &le, count: 2))
    }

    mutating func append(littleEndian value: UInt32) {
        var le = value.littleEndian
        append(Data(bytes: &le, count: 4))
    }
}
