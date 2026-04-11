package co.crackn.kompressor.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts 16-bit signed little-endian PCM between sample rates and channel layouts.
 *
 * Internally, audio data is represented as a flat interleaved [ShortArray] to minimise
 * per-frame heap allocations on the hot transcode path.
 *
 * Resampling uses linear interpolation with a fractional phase accumulator so that
 * it can be called repeatedly on streaming chunks without drift.  When downsampling,
 * a single-pole low-pass filter at the target Nyquist is applied first to reduce
 * aliasing.
 *
 * Channel conversion is applied **after** resampling:
 * - stereo → mono: `(L + R) / 2`
 * - mono → stereo: sample duplicated to both channels
 *
 * @param inputRate  Source sample rate in Hz.
 * @param outputRate Target sample rate in Hz.
 * @param inputChannels  Number of channels in the source PCM (1 or 2).
 * @param outputChannels Number of channels in the target PCM (1 or 2).
 */
internal class PcmProcessor(
    private val inputRate: Int,
    private val outputRate: Int,
    private val inputChannels: Int,
    private val outputChannels: Int,
) {
    init {
        require(inputChannels == MONO_CHANNELS || inputChannels == STEREO_CHANNELS) {
            "Unsupported input channel count: $inputChannels"
        }
        require(outputChannels == MONO_CHANNELS || outputChannels == STEREO_CHANNELS) {
            "Unsupported output channel count: $outputChannels"
        }
    }

    private val needsResample = inputRate != outputRate
    private val needsChannelConvert = inputChannels != outputChannels
    private val ratio: Double = outputRate.toDouble() / inputRate.toDouble()

    // Phase accumulator for streaming linear interpolation (fractional input index).
    private var phase: Double = 0.0

    // Single-pole low-pass filter state per input channel (used only when downsampling).
    private val lpfState = DoubleArray(inputChannels)
    private val applyLpf = needsResample && outputRate < inputRate
    private val lpfAlpha = if (applyLpf) computeLpfAlpha(inputRate, outputRate) else 1.0

    // Reusable output buffer — grown as needed, never shrunk within a stream.
    private var outBuf = ByteBuffer.allocate(INITIAL_OUT_BUF).order(ByteOrder.LITTLE_ENDIAN)

    // Last frame of the previous chunk, prepended to the next for cross-boundary interpolation.
    private var lastChunkFrame: ShortArray? = null

    /**
     * Processes a chunk of PCM and returns a [ByteBuffer] with the converted result.
     *
     * [input] must be positioned at the start of valid data with its limit set correctly.
     * The returned buffer is ready to read (position = 0, limit = valid data length).
     * The buffer is owned by this processor and is only valid until the next [process] call.
     */
    fun process(input: ByteBuffer): ByteBuffer {
        input.order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = input.remaining() / (inputChannels * BYTES_PER_SAMPLE)
        val samples = readFlat(input, frameCount)

        var data = samples
        var frames = frameCount
        var channels = inputChannels

        if (applyLpf) applyLowPassInPlace(data, frames, channels)
        if (needsResample) {
            data = resample(data, frames, channels)
            frames = data.size / channels
        }
        if (needsChannelConvert) {
            data = convertChannels(data, frames, channels)
            channels = outputChannels
            frames = data.size / channels
        }

        return toByteBuffer(data, frames, channels)
    }

    // ── Internal helpers ────────────────────────────────────────────

    /** Reads interleaved PCM into a flat [ShortArray]. */
    private fun readFlat(buf: ByteBuffer, frameCount: Int): ShortArray {
        val total = frameCount * inputChannels
        val flat = ShortArray(total)
        for (i in 0 until total) flat[i] = buf.short
        return flat
    }

    /** Applies single-pole IIR low-pass filter in place. */
    private fun applyLowPassInPlace(
        data: ShortArray,
        frameCount: Int,
        channels: Int,
    ) {
        for (i in 0 until frameCount) {
            val base = i * channels
            for (ch in 0 until channels) {
                val x = data[base + ch].toDouble()
                lpfState[ch] += lpfAlpha * (x - lpfState[ch])
                data[base + ch] = lpfState[ch].toInt().coerceIn(
                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(),
                ).toShort()
            }
        }
    }

    /**
     * Resamples interleaved PCM via linear interpolation.
     *
     * Maintains streaming continuity across chunk boundaries by prepending the last
     * frame of the previous chunk to the current input.  When the interpolation index
     * would require the first frame of the *next* chunk (i.e. at the final boundary),
     * the loop stops and defers that output to the following [process] call.  This
     * eliminates the periodic discontinuity that the hold-last-value approach produced
     * at every chunk boundary.
     */
    @Suppress("NestedBlockDepth")
    private fun resample(
        data: ShortArray,
        frameCount: Int,
        channels: Int,
    ): ShortArray {
        if (frameCount == 0) return ShortArray(0)

        // Prepend last frame from previous chunk for seamless cross-boundary interpolation.
        val prev = lastChunkFrame
        val extData: ShortArray
        val extFrameCount: Int
        val phaseOffset: Int
        if (prev != null) {
            extData = ShortArray(channels + data.size)
            prev.copyInto(extData, 0)
            data.copyInto(extData, channels)
            extFrameCount = frameCount + 1
            phaseOffset = 1
        } else {
            extData = data
            extFrameCount = frameCount
            phaseOffset = 0
        }

        // Save the last frame of the current chunk for the next call.
        lastChunkFrame = data.copyOfRange((frameCount - 1) * channels, frameCount * channels)

        val estOutput = ((frameCount * ratio) + 2).toInt()
        val out = ShortArray(estOutput * channels)
        var written = 0

        phase += phaseOffset
        while (phase < extFrameCount) {
            val idx = phase.toInt()
            val frac = phase - idx
            // Stop before the last frame: interpolation between the last frame of this
            // chunk and the first frame of the next chunk is handled in the next call.
            if (idx + 1 >= extFrameCount) break
            val base0 = idx * channels
            val base1 = (idx + 1) * channels
            for (ch in 0 until channels) {
                val s0 = extData[base0 + ch].toDouble()
                val s1 = extData[base1 + ch].toDouble()
                out[written++] = (s0 + (s1 - s0) * frac).toInt().coerceIn(
                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(),
                ).toShort()
            }
            phase += 1.0 / ratio
        }
        phase -= extFrameCount

        return out.copyOf(written)
    }

    private fun convertChannels(
        data: ShortArray,
        frameCount: Int,
        inChannels: Int,
    ): ShortArray =
        if (inChannels == STEREO_CHANNELS && outputChannels == MONO_CHANNELS) {
            ShortArray(frameCount) { i ->
                ((data[i * 2].toInt() + data[i * 2 + 1].toInt()) / 2).toShort()
            }
        } else {
            // mono → stereo: duplicate
            ShortArray(frameCount * 2) { i ->
                data[i / 2]
            }
        }

    private fun toByteBuffer(
        data: ShortArray,
        frameCount: Int,
        channels: Int,
    ): ByteBuffer {
        val needed = frameCount * channels * BYTES_PER_SAMPLE
        if (outBuf.capacity() < needed) {
            outBuf = ByteBuffer.allocate(needed).order(ByteOrder.LITTLE_ENDIAN)
        }
        outBuf.clear()
        for (i in 0 until frameCount * channels) {
            outBuf.putShort(data[i])
        }
        outBuf.flip()
        return outBuf
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MONO_CHANNELS = 1
        const val STEREO_CHANNELS = 2
        const val INITIAL_OUT_BUF = 16_384

        /** Compute single-pole IIR low-pass coefficient: α = 2πfc / (2πfc + fs). */
        fun computeLpfAlpha(inputRate: Int, outputRate: Int): Double {
            val cutoff = outputRate / 2.0 // Nyquist of target rate
            val rc = 1.0 / (2.0 * Math.PI * cutoff)
            val dt = 1.0 / inputRate
            return dt / (rc + dt)
        }
    }
}
