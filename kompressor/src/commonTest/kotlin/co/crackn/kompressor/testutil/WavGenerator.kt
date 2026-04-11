package co.crackn.kompressor.testutil

import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates a valid RIFF WAV file as a [ByteArray] using pure Kotlin — no platform dependencies.
 *
 * Each channel gets a distinct sine-wave frequency (440 Hz * (channelIndex + 1)) so that
 * channel-mixing logic can be verified downstream.
 */
object WavGenerator {

    fun generateWavBytes(
        durationSeconds: Int,
        sampleRate: Int,
        channels: Int,
        toneFrequency: Double = TONE_FREQUENCY,
    ): ByteArray {
        val totalSamples = sampleRate * durationSeconds
        val dataSize = totalSamples * channels * BYTES_PER_SAMPLE
        val bytes = ByteArray(WAV_HEADER_SIZE + dataSize)

        // RIFF header
        writeString(bytes, 0, "RIFF")
        writeIntLE(bytes, 4, WAV_HEADER_SIZE - RIFF_CHUNK_HEADER + dataSize)
        writeString(bytes, 8, "WAVE")

        // fmt sub-chunk
        writeString(bytes, 12, "fmt ")
        writeIntLE(bytes, 16, PCM_FMT_CHUNK_SIZE)
        writeShortLE(bytes, 20, PCM_FORMAT)
        writeShortLE(bytes, 22, channels)
        writeIntLE(bytes, 24, sampleRate)
        writeIntLE(bytes, 28, sampleRate * channels * BYTES_PER_SAMPLE)
        writeShortLE(bytes, 32, channels * BYTES_PER_SAMPLE)
        writeShortLE(bytes, 34, BITS_PER_SAMPLE)

        // data sub-chunk
        writeString(bytes, 36, "data")
        writeIntLE(bytes, 40, dataSize)

        // PCM sine waves — distinct frequencies per channel
        var offset = WAV_HEADER_SIZE
        for (i in 0 until totalSamples) {
            for (ch in 0 until channels) {
                val frequency = toneFrequency * (ch + 1)
                val sample = (Short.MAX_VALUE * sin(2.0 * PI * frequency * i / sampleRate))
                    .toInt().toShort()
                bytes[offset++] = (sample.toInt() and 0xFF).toByte()
                bytes[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }

        return bytes
    }

    private fun writeString(bytes: ByteArray, offset: Int, value: String) {
        for (i in value.indices) {
            bytes[offset + i] = value[i].code.toByte()
        }
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private const val TONE_FREQUENCY = 440.0
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT = 1
    private const val PCM_FMT_CHUNK_SIZE = 16
    private const val WAV_HEADER_SIZE = 44
    private const val RIFF_CHUNK_HEADER = 8
}
