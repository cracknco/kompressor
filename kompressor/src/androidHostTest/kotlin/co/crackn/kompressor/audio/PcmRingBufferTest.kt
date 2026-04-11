package co.crackn.kompressor.audio

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmRingBufferTest {

    // ── Basic write / read ─────────────────────────────────────────────

    @Test
    fun writeAndReadChunk_returnsExactBytes() {
        val buf = PcmRingBuffer()
        buf.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val dest = ByteBuffer.allocate(8)
        val read = buf.readChunk(dest)
        assertEquals(8, read)
        dest.rewind()
        assertEquals(1, dest.get())
        assertEquals(8, dest.get(7))
    }

    @Test
    fun hasChunk_trueWhenSufficient_falseWhenNot() {
        val buf = PcmRingBuffer()
        buf.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        assertTrue(buf.hasChunk(3))
        assertFalse(buf.hasChunk(4))
    }

    @Test
    fun flush_drainsAllAvailable() {
        val buf = PcmRingBuffer()
        buf.write(ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 50)))
        val dest = ByteBuffer.allocate(10)
        val flushed = buf.flush(dest)
        assertEquals(5, flushed)
        assertFalse(buf.hasRemaining())
    }

    @Test
    fun hasRemaining_defaultFrameSize_trueForAnyByte() {
        val buf = PcmRingBuffer()
        assertFalse(buf.hasRemaining())
        buf.write(ByteBuffer.wrap(byteArrayOf(1)))
        assertTrue(buf.hasRemaining())
    }

    // ── Frame-alignment ────────────────────────────────────────────────

    @Test
    fun readChunk_alignsDownToFrameSize() {
        val buf = PcmRingBuffer(frameSize = 4)
        buf.write(ByteBuffer.wrap(ByteArray(10) { it.toByte() }))
        val dest = ByteBuffer.allocate(10) // capacity=10, frameSize=4 → reads 8
        val read = buf.readChunk(dest)
        assertEquals(8, read)
    }

    @Test
    fun readChunk_exactMultiple_readsAll() {
        val buf = PcmRingBuffer(frameSize = 4)
        buf.write(ByteBuffer.wrap(ByteArray(8) { it.toByte() }))
        val dest = ByteBuffer.allocate(8)
        val read = buf.readChunk(dest)
        assertEquals(8, read)
    }

    @Test
    fun flush_alignsDownToFrameSize() {
        val buf = PcmRingBuffer(frameSize = 4)
        buf.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))) // 6 bytes → 1 complete frame + 2 leftover
        val dest = ByteBuffer.allocate(10)
        val flushed = buf.flush(dest)
        assertEquals(4, flushed) // only one complete 4-byte frame
    }

    @Test
    fun flush_returnsZeroWhenLessThanOneFrame() {
        val buf = PcmRingBuffer(frameSize = 4)
        buf.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        val dest = ByteBuffer.allocate(10)
        assertEquals(0, buf.flush(dest))
    }

    @Test
    fun hasRemaining_requiresAtLeastOneCompleteFrame() {
        val buf = PcmRingBuffer(frameSize = 4)
        buf.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        assertFalse(buf.hasRemaining()) // 3 < frameSize=4
        buf.write(ByteBuffer.wrap(byteArrayOf(4)))
        assertTrue(buf.hasRemaining()) // now 4 == frameSize=4
    }

    // ── Constructor validation ─────────────────────────────────────────

    @Test
    fun constructor_zeroFrameSize_throws() {
        assertFailsWith<IllegalArgumentException> {
            PcmRingBuffer(frameSize = 0)
        }
    }

    @Test
    fun constructor_negativeFrameSize_throws() {
        assertFailsWith<IllegalArgumentException> {
            PcmRingBuffer(frameSize = -1)
        }
    }

    // ── Multiple reads and compaction ──────────────────────────────────

    @Test
    fun multipleWriteAndRead_dataPreservedAcrossCompaction() {
        val buf = PcmRingBuffer()
        // Write enough to fill past the compaction midpoint
        val chunk1 = ByteArray(9_000) { it.toByte() }
        buf.write(ByteBuffer.wrap(chunk1))
        val dest1 = ByteBuffer.allocate(9_000)
        buf.readChunk(dest1) // readPos now past midpoint → compaction on next read

        val chunk2 = ByteArray(100) { (it + 42).toByte() }
        buf.write(ByteBuffer.wrap(chunk2))
        val dest2 = ByteBuffer.allocate(100)
        val flushed = buf.flush(dest2)
        assertEquals(100, flushed)
        dest2.rewind()
        assertEquals(42.toByte(), dest2.get())
    }

    @Test
    fun flush_doesNotWriteBeyondDestCapacity() {
        val buf = PcmRingBuffer()
        buf.write(ByteBuffer.wrap(ByteArray(200)))
        val dest = ByteBuffer.allocate(50)
        val flushed = buf.flush(dest)
        assertEquals(50, flushed) // capped by dest.capacity()
    }

    // ── Size property ─────────────────────────────────────────────────

    @Test
    fun size_reflectsUnreadBytes() {
        val buf = PcmRingBuffer()
        assertEquals(0, buf.size)
        buf.write(ByteBuffer.wrap(ByteArray(100)))
        assertEquals(100, buf.size)
        buf.readChunk(ByteBuffer.allocate(40))
        assertEquals(60, buf.size)
    }

    // ── Max capacity safety net ───────────────────────────────────────

    @Test
    fun write_beyondMaxCapacity_throws() {
        val buf = PcmRingBuffer(maxCapacity = PcmRingBuffer.INITIAL_CAPACITY)
        val chunk = ByteArray(PcmRingBuffer.INITIAL_CAPACITY + 1)
        assertFailsWith<IllegalStateException> {
            buf.write(ByteBuffer.wrap(chunk))
        }
    }

    @Test
    fun write_withinMaxCapacity_succeeds() {
        val buf = PcmRingBuffer(maxCapacity = PcmRingBuffer.INITIAL_CAPACITY * 2)
        val chunk = ByteArray(PcmRingBuffer.INITIAL_CAPACITY + 100)
        buf.write(ByteBuffer.wrap(chunk))
        assertEquals(PcmRingBuffer.INITIAL_CAPACITY + 100, buf.size)
    }
}
