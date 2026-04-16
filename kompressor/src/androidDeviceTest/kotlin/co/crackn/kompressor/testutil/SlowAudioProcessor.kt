/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * An [AudioProcessor] that copies its input to its output unchanged but blocks the Media3
 * audio pipeline thread for [delayPerBufferMs] on every `queueInput` call. Used by cancellation
 * and timeout device tests to deterministically keep the encoder busy mid-export regardless
 * of the device's encoder throughput — without this, fast hardware (Samsung A53, Pixel 8,
 * …) can finish a full transcode before the test's cancellation handshake lands, making the
 * test vacuously pass.
 *
 * This is a test-only fixture injected via [AndroidAudioCompressor]'s `testExtraAudioProcessors`
 * constructor parameter. It never ships in the production graph.
 */
class SlowAudioProcessor(private val delayPerBufferMs: Long) : BaseAudioProcessor() {
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (delayPerBufferMs > 0) {
            try {
                Thread.sleep(delayPerBufferMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer).flip()
    }
}
