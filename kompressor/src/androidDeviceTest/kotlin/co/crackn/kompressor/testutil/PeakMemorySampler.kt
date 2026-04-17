/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.os.Debug
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Background sampler that records the peak process PSS over the sampler's lifetime.
 *
 * Android sibling of `PeakMemorySampler` (iOS). Used by `LargeVideoInputStreamingTest` to verify
 * the Media3 pipeline streams rather than loading the whole input into RAM. Samples run on a
 * dedicated thread so transient spikes during the async compression are caught.
 *
 * PSS (Proportional Set Size) via [Debug.getPss] covers both Java heap and native memory
 * (the latter is where MediaCodec / Transformer do their real work), which is the closest
 * Android analogue to iOS's `phys_footprint`.
 */
class PeakMemorySampler(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val running = AtomicBoolean(false)
    private val peakBytes = AtomicLong(0)
    private var thread: Thread? = null

    /** Begin sampling on a background thread. Must be paired with exactly one [stop] call. */
    fun start() {
        check(running.compareAndSet(false, true)) { "PeakMemorySampler already running" }
        peakBytes.set(currentPssBytes())
        thread = Thread({ sampleLoop() }, "kompressor-peak-mem-sampler").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stop sampling, take one final reading, and return the peak PSS in bytes observed between
     * [start] and [stop]. Blocks briefly while the sampler thread joins.
     */
    fun stop(): Long {
        running.set(false)
        thread?.join(STOP_JOIN_TIMEOUT_MS)
        thread = null
        val finalSample = currentPssBytes()
        peakBytes.updateAndGet { if (finalSample > it) finalSample else it }
        return peakBytes.get()
    }

    private fun sampleLoop() {
        while (running.get()) {
            val current = currentPssBytes()
            peakBytes.updateAndGet { if (current > it) current else it }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun currentPssBytes(): Long = Debug.getPss() * BYTES_PER_KB

    private companion object {
        const val DEFAULT_INTERVAL_MS = 50L
        const val BYTES_PER_KB = 1_024L
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
