package co.crackn.kompressor.audio

import java.nio.ByteBuffer

/**
 * A byte-level accumulation buffer that decouples variable-sized decoder
 * output from the fixed-size [android.media.MediaCodec] encoder input buffers.
 *
 * Uses a read-pointer to avoid shifting data on every [readChunk] call.
 * The backing array is compacted only when the read pointer has consumed
 * more than half the capacity, amortising the copy cost.
 */
internal class PcmRingBuffer {

    private var data = ByteArray(INITIAL_CAPACITY)
    private var readPos = 0
    private var writePos = 0

    /** Number of unread bytes. */
    private val available: Int get() = writePos - readPos

    /** Appends all remaining bytes from [src] into the buffer. */
    fun write(src: ByteBuffer) {
        val count = src.remaining()
        if (count == 0) return
        ensureCapacity(writePos + count)
        src.get(data, writePos, count)
        writePos += count
    }

    /** Returns `true` when at least [chunkSize] bytes are available. */
    fun hasChunk(chunkSize: Int): Boolean = available >= chunkSize

    /** Returns `true` when any data remains (even less than a full chunk). */
    fun hasRemaining(): Boolean = available > 0

    /**
     * Copies exactly [dest]`.capacity()` bytes into [dest] and removes them
     * from the buffer.  The caller must ensure [hasChunk] returned `true`
     * for this capacity first.
     *
     * @return the number of bytes written.
     */
    fun readChunk(dest: ByteBuffer): Int {
        val chunkSize = dest.capacity()
        require(available >= chunkSize) { "Not enough data: $available < $chunkSize" }
        dest.clear()
        dest.put(data, readPos, chunkSize)
        dest.flip()
        readPos += chunkSize
        compactIfNeeded()
        return chunkSize
    }

    /**
     * Drains all remaining bytes into [dest].
     * Used at end-of-stream for the final partial chunk.
     *
     * @return the number of bytes written.
     */
    fun flush(dest: ByteBuffer): Int {
        val count = minOf(available, dest.capacity())
        dest.clear()
        dest.put(data, readPos, count)
        dest.flip()
        readPos += count
        compactIfNeeded()
        return count
    }

    /** Shifts remaining data to the front when the read pointer passes the midpoint. */
    private fun compactIfNeeded() {
        if (readPos <= data.size / 2) return
        val remaining = available
        if (remaining > 0) {
            System.arraycopy(data, readPos, data, 0, remaining)
        }
        readPos = 0
        writePos = remaining
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var newCap = data.size
        while (newCap < needed) newCap *= 2
        data = data.copyOf(newCap)
        // readPos and writePos remain valid — data was only extended.
    }

    private companion object {
        const val INITIAL_CAPACITY = 16_384
    }
}
