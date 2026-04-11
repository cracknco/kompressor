package co.crackn.kompressor.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcmProcessorTest {

    // ── Constructor validation ─────────────────────────────────────────

    @Test
    fun constructor_unsupportedInputChannels_throws() {
        assertFailsWith<IllegalArgumentException> {
            PcmProcessor(44_100, 44_100, inputChannels = 3, outputChannels = 1)
        }
    }

    @Test
    fun constructor_unsupportedOutputChannels_throws() {
        assertFailsWith<IllegalArgumentException> {
            PcmProcessor(44_100, 44_100, inputChannels = 1, outputChannels = 3)
        }
    }

    @Test
    fun constructor_zeroInputChannels_throws() {
        assertFailsWith<IllegalArgumentException> {
            PcmProcessor(44_100, 44_100, inputChannels = 0, outputChannels = 1)
        }
    }

    // ── No-op pass-through ─────────────────────────────────────────────

    @Test
    fun process_sameRateSameChannels_passesThrough() {
        val proc = PcmProcessor(44_100, 44_100, inputChannels = 1, outputChannels = 1)
        val input = monoBuffer(shortArrayOf(100, 200, -300, 400))
        val out = readShorts(proc.process(input))
        assertEquals(listOf<Short>(100, 200, -300, 400), out)
    }

    // ── Channel conversion ─────────────────────────────────────────────

    @Test
    fun process_stereoToMono_averagesLAndR() {
        val proc = PcmProcessor(44_100, 44_100, inputChannels = 2, outputChannels = 1)
        // Frame: L=100, R=300 → mono = (100+300)/2 = 200
        val input = interleavedBuffer(shortArrayOf(100, 300))
        val out = readShorts(proc.process(input))
        assertEquals(1, out.size)
        assertEquals(200.toShort(), out[0])
    }

    @Test
    fun process_monoToStereo_duplicatesSample() {
        val proc = PcmProcessor(44_100, 44_100, inputChannels = 1, outputChannels = 2)
        val input = monoBuffer(shortArrayOf(500, -500))
        val out = readShorts(proc.process(input))
        assertEquals(4, out.size)
        assertEquals(500.toShort(), out[0])
        assertEquals(500.toShort(), out[1])
        assertEquals((-500).toShort(), out[2])
        assertEquals((-500).toShort(), out[3])
    }

    // ── Sample rate conversion ─────────────────────────────────────────

    @Test
    fun process_downsampling_outputFrameCountApproximate() {
        // 48 000 → 22 050: ratio ≈ 0.459
        val proc = PcmProcessor(48_000, 22_050, inputChannels = 1, outputChannels = 1)
        val inputFrames = 480
        val out = proc.process(silentMono(inputFrames))
        val outputFrames = out.remaining() / BYTES_PER_SAMPLE
        val expected = (inputFrames * 22_050.0 / 48_000).toInt()
        assertTrue(abs(outputFrames - expected) <= 2, "outputFrames=$outputFrames expected≈$expected")
    }

    @Test
    fun process_upsampling_outputFrameCountApproximate() {
        // 22 050 → 44 100: ratio = 2.0
        val proc = PcmProcessor(22_050, 44_100, inputChannels = 1, outputChannels = 1)
        val inputFrames = 100
        val out = proc.process(silentMono(inputFrames))
        val outputFrames = out.remaining() / BYTES_PER_SAMPLE
        val expected = (inputFrames * 44_100.0 / 22_050).toInt()
        assertTrue(abs(outputFrames - expected) <= 2, "outputFrames=$outputFrames expected≈$expected")
    }

    // ── Cross-chunk continuity ─────────────────────────────────────────

    @Test
    fun process_splitIntoTwoChunks_sameTotalSampleCount() {
        // Processing as one big chunk vs two halves should yield the same total output size.
        val inputFrames = 200
        val half = inputFrames / 2

        val procFull = PcmProcessor(48_000, 44_100, inputChannels = 1, outputChannels = 1)
        val fullOut = readShorts(procFull.process(rampMono(0, inputFrames)))

        val procSplit = PcmProcessor(48_000, 44_100, inputChannels = 1, outputChannels = 1)
        val half1 = readShorts(procSplit.process(rampMono(0, half)))
        val half2 = readShorts(procSplit.process(rampMono(half, inputFrames)))
        val splitTotal = half1.size + half2.size

        assertTrue(
            abs(fullOut.size - splitTotal) <= 2,
            "full=${fullOut.size} split=$splitTotal",
        )
    }

    @Test
    fun process_crossChunkBoundary_noSilentGap() {
        // Verify the cross-chunk interpolation produces a value between the boundary frames,
        // not a hold-last-value repetition (which would be just the last sample of chunk 1).
        val proc = PcmProcessor(48_000, 44_100, inputChannels = 1, outputChannels = 1)

        // Chunk 1: ramps from 0 to 999
        val chunk1 = rampMono(0, 100)
        val out1 = readShorts(proc.process(chunk1))

        // Chunk 2: ramps from 1000 to 1099
        val chunk2 = rampMono(1_000, 100)
        val out2 = readShorts(proc.process(chunk2))

        // The first sample of out2 must interpolate between the last frame of chunk1 (99)
        // and the first frame of chunk2 (1000), so it should be between those two values.
        assertTrue(out2.isNotEmpty(), "chunk 2 output was empty")
        val boundary = out2.first().toInt()
        assertTrue(
            boundary in 100..1_000,
            "boundary sample $boundary should interpolate between 99 and 1000",
        )
    }

    @Test
    fun process_stereoDownsampleAndConvert_outputIsFrameAligned() {
        // 48k stereo → 22.05k mono: output bytes must be a multiple of 2 (mono, 2 bytes/frame)
        val proc = PcmProcessor(48_000, 22_050, inputChannels = 2, outputChannels = 1)
        val out = proc.process(silentStereo(1_000))
        assertEquals(0, out.remaining() % BYTES_PER_SAMPLE)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun monoBuffer(samples: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        buf.flip()
        return buf
    }

    private fun interleavedBuffer(samples: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        buf.flip()
        return buf
    }

    private fun silentMono(frames: Int): ByteBuffer = monoBuffer(ShortArray(frames))

    private fun silentStereo(frames: Int): ByteBuffer = interleavedBuffer(ShortArray(frames * 2))

    private fun rampMono(start: Int, frameCount: Int): ByteBuffer =
        monoBuffer(ShortArray(frameCount) { (start + it).toShort() })

    private fun readShorts(buf: ByteBuffer): List<Short> {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableListOf<Short>()
        while (buf.hasRemaining()) result.add(buf.short)
        return result
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}