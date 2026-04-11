package co.crackn.kompressor.audio

import java.nio.ByteBuffer

/**
 * A byte-level accumulation buffer that decouples variable-sized decoder
 * output from the fixed-size [android.media.MediaCodec] encoder input buffers.
 *
 * Uses a read-pointer to avoid shifting data on every [readChunk] call.
 * The backing array is compacted only when the read pointer has consumed
 * more than half the capacity, amortising the copy cost.
 *
 * @param frameSize The PCM frame size in bytes (channels × bytes-per-sample).
 *   Reads are rounded down to the nearest multiple of [frameSize] so that
 *   encoder input buffers are never split in the middle of a PCM frame.
 */
internal class PcmRingBuffer(
    private val frameSize: Int = 1,
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
) {
    init {
        require(frameSize >= 1) { "frameSize must be >= 1" }
        require(maxCapacity >= INITIAL_CAPACITY) {
            "maxCapacity must be >= $INITIAL_CAPACITY"
        }
    }

    private var data = ByteArray(INITIAL_CAPACITY)
    private var readPos = 0
    private var writePos = 0

    /** Number of unread bytes. */
    val size: Int get() = writePos - readPos

    private val available: Int get() = size

    /** Appends all remaining bytes from [src] into the buffer. */
    fun write(src: ByteBuffer) {
        val count = src.remaining()
        if (count == 0) return
        ensureCapacity(count)
        src.get(data, writePos, count)
        writePos += count
    }

    /** Returns `true` when at least [chunkSize] bytes are available. */
    fun hasChunk(chunkSize: Int): Boolean = available >= chunkSize

    /** Returns `true` when at least one complete frame remains to be flushed. */
    fun hasRemaining(): Boolean = available >= frameSize

    /**
     * Copies bytes into [dest], rounded down to the nearest [frameSize] multiple,
     * and removes them from the buffer.  The caller must ensure [hasChunk] returned
     * `true` for [dest]`.capacity()` first.
     *
     * Rounding down guarantees the encoder input buffer is never split mid-frame,
     * which would corrupt the PCM layout and break PTS accounting.
     *
     * @return the number of bytes written (a multiple of [frameSize]).
     */
    fun readChunk(dest: ByteBuffer): Int {
        val chunkSize = dest.capacity() / frameSize * frameSize
        require(available >= chunkSize) { "Not enough data: $available < $chunkSize" }
        dest.clear()
        dest.put(data, readPos, chunkSize)
        dest.flip()
        readPos += chunkSize
        compactIfNeeded()
        return chunkSize
    }

    /**
     * Drains all remaining complete frames into [dest].
     * Used at end-of-stream for the final partial chunk.
     *
     * Any trailing bytes that form an incomplete frame are left in the buffer
     * rather than being split across encoder input buffers.
     *
     * @return the number of bytes written (a multiple of [frameSize]).
     */
    fun flush(dest: ByteBuffer): Int {
        val count = minOf(available, dest.capacity()) / frameSize * frameSize
        if (count == 0) return 0
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

    private fun ensureCapacity(bytesToAppend: Int) {
        if (writePos + bytesToAppend <= data.size) return

        // Compact first — reclaim dead space before readPos.
        if (readPos > 0) {
            val remaining = size
            if (remaining > 0) {
                System.arraycopy(data, readPos, data, 0, remaining)
            }
            readPos = 0
            writePos = remaining
        }

        val needed = writePos + bytesToAppend
        check(needed <= maxCapacity) {
            "PcmRingBuffer exceeded max capacity of $maxCapacity bytes " +
                "(requested $needed) — backpressure logic may be broken"
        }
        if (needed <= data.size) return

        var newCap = data.size
        while (newCap < needed) newCap *= 2
        data = data.copyOf(newCap.coerceAtMost(maxCapacity))
    }

    internal companion object {
        const val INITIAL_CAPACITY = 16_384
        const val DEFAULT_MAX_CAPACITY = 8 * 1024 * 1024 // 8 MB safety net
    }
}
