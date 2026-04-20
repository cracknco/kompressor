/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates a valid RIFF WAV file as a [ByteArray] using pure Kotlin — no platform dependencies.
 *
 * By default each channel gets a distinct sine-wave frequency (`toneFrequency * (channelIndex + 1)`)
 * so that channel-mixing logic can be verified downstream. Pass
 * [perChannelFrequencyMultiplier] = `{ 1.0 }` when every channel should carry the same tone
 * (required when the consumer mono-downmixes at read time and expects a single dominant peak).
 * [bitsPerSample] selects the PCM sample width ([8], [16], [24], or [32]); the default [16]
 * keeps existing callers byte-identical. 8-bit WAV samples are unsigned per the RIFF spec; all
 * wider widths are signed little-endian.
 */
object WavGenerator {

    @Suppress("LongParameterList")
    fun generateWavBytes(
        durationSeconds: Int,
        sampleRate: Int,
        channels: Int,
        toneFrequency: Double = TONE_FREQUENCY,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
        perChannelFrequencyMultiplier: (channelIndex: Int) -> Double = { (it + 1).toDouble() },
    ): ByteArray {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
        require(durationSeconds > 0) { "durationSeconds must be > 0, was $durationSeconds" }
        require(channels > 0) { "channels must be > 0, was $channels" }
        require(bitsPerSample in setOf(BITS_8, BITS_16, BITS_24, BITS_32)) {
            "bitsPerSample must be 8, 16, 24, or 32, was $bitsPerSample"
        }

        val bytesPerSample = bitsPerSample / BITS_PER_BYTE
        val totalSamples = sampleRate.toLong() * durationSeconds
        val dataSize = totalSamples * channels * bytesPerSample
        require(dataSize <= Int.MAX_VALUE) { "WAV data too large: $dataSize bytes" }
        val dataSizeInt = dataSize.toInt()
        val bytes = ByteArray(WAV_HEADER_SIZE + dataSizeInt)

        // RIFF header
        writeString(bytes, 0, "RIFF")
        writeIntLE(bytes, 4, WAV_HEADER_SIZE - RIFF_CHUNK_HEADER + dataSizeInt)
        writeString(bytes, 8, "WAVE")

        // fmt sub-chunk
        writeString(bytes, 12, "fmt ")
        writeIntLE(bytes, 16, PCM_FMT_CHUNK_SIZE)
        writeShortLE(bytes, 20, PCM_FORMAT)
        writeShortLE(bytes, 22, channels)
        writeIntLE(bytes, 24, sampleRate)
        writeIntLE(bytes, 28, sampleRate * channels * bytesPerSample)
        writeShortLE(bytes, 32, channels * bytesPerSample)
        writeShortLE(bytes, 34, bitsPerSample)

        // data sub-chunk
        writeString(bytes, 36, "data")
        writeIntLE(bytes, 40, dataSizeInt)

        // PCM sine waves — per-channel frequency controlled by the caller-supplied multiplier.
        var offset = WAV_HEADER_SIZE
        for (i in 0L until totalSamples) {
            for (ch in 0 until channels) {
                val frequency = toneFrequency * perChannelFrequencyMultiplier(ch)
                val unit = sin(2.0 * PI * frequency * i / sampleRate)
                offset = writeSample(bytes, offset, unit, bitsPerSample)
            }
        }

        return bytes
    }

    /**
     * Write a single PCM sample at [offset] in [bytes], scaling the [-1.0, 1.0] unit value into
     * the signed/unsigned range for the requested [bitsPerSample]. Returns the new offset.
     */
    private fun writeSample(bytes: ByteArray, offset: Int, unit: Double, bitsPerSample: Int): Int {
        when (bitsPerSample) {
            BITS_8 -> {
                // RIFF 8-bit PCM is unsigned, centred at 128.
                val v = (UNSIGNED_8_BIT_CENTER + unit * UNSIGNED_8_BIT_CENTER).toInt()
                    .coerceIn(0, UNSIGNED_8_BIT_MAX)
                bytes[offset] = v.toByte()
                return offset + 1
            }
            BITS_16 -> {
                val v = (unit * Short.MAX_VALUE).toInt()
                bytes[offset] = (v and 0xFF).toByte()
                bytes[offset + 1] = ((v shr BYTE_SHIFT_1) and 0xFF).toByte()
                return offset + 2
            }
            BITS_24 -> {
                val v = (unit * SIGNED_24_BIT_MAX).toInt()
                bytes[offset] = (v and 0xFF).toByte()
                bytes[offset + 1] = ((v shr BYTE_SHIFT_1) and 0xFF).toByte()
                bytes[offset + 2] = ((v shr BYTE_SHIFT_2) and 0xFF).toByte()
                return offset + 3
            }
            BITS_32 -> {
                val v = (unit * Int.MAX_VALUE).toLong().toInt()
                bytes[offset] = (v and 0xFF).toByte()
                bytes[offset + 1] = ((v shr BYTE_SHIFT_1) and 0xFF).toByte()
                bytes[offset + 2] = ((v shr BYTE_SHIFT_2) and 0xFF).toByte()
                bytes[offset + 3] = ((v shr BYTE_SHIFT_3) and 0xFF).toByte()
                return offset + 4
            }
            else -> error("unreachable: bitsPerSample=$bitsPerSample")
        }
    }

    private fun writeString(bytes: ByteArray, offset: Int, value: String) {
        for (i in value.indices) {
            bytes[offset + i] = value[i].code.toByte()
        }
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr BYTE_SHIFT_1) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr BYTE_SHIFT_2) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr BYTE_SHIFT_3) and 0xFF).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr BYTE_SHIFT_1) and 0xFF).toByte()
    }

    private const val TONE_FREQUENCY = 440.0
    private const val DEFAULT_BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT = 1
    private const val PCM_FMT_CHUNK_SIZE = 16
    private const val WAV_HEADER_SIZE = 44
    private const val RIFF_CHUNK_HEADER = 8

    private const val BITS_PER_BYTE = 8
    private const val BITS_8 = 8
    private const val BITS_16 = 16
    private const val BITS_24 = 24
    private const val BITS_32 = 32

    private const val BYTE_SHIFT_1 = 8
    private const val BYTE_SHIFT_2 = 16
    private const val BYTE_SHIFT_3 = 24

    private const val UNSIGNED_8_BIT_CENTER = 128.0
    private const val UNSIGNED_8_BIT_MAX = 255
    private const val SIGNED_24_BIT_MAX = 8_388_607.0 // 2^23 - 1
}
