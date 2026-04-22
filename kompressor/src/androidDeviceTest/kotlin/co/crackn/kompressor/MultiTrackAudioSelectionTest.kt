/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.testutil.MultiTrackAudioFixture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Device-level tests for `AudioCompressionConfig.audioTrackIndex`: the requested track's tone
 * must survive the compress round-trip and the probe must report the correct audio track count.
 */
class MultiTrackAudioSelectionTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()
    private val kompressor = AndroidKompressor(NoOpLogger)

    private companion object {
        const val DURATION_SEC = 2
        const val TONE_A = 440
        const val TONE_B = 880
        const val PEAK_TOLERANCE_HZ = 10
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-multi-track-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressAudio_selectsTrack0() = runTest {
        val input = buildTwoTrackFixture()
        val output = File(tempDir, "out0.m4a")
        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(audioTrackIndex = 0),
        )
        assertTrue(result.isSuccess, "result=$result")
        val peak = dominantFrequency(output)
        assertTrue(
            abs(peak - TONE_A) <= PEAK_TOLERANCE_HZ,
            "expected ~$TONE_A Hz, got $peak",
        )
    }

    @Test
    fun compressAudio_selectsTrack1() = runTest {
        val input = buildTwoTrackFixture()
        val output = File(tempDir, "out1.m4a")
        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(audioTrackIndex = 1),
        )
        assertTrue(result.isSuccess, "result=$result")
        val peak = dominantFrequency(output)
        assertTrue(
            abs(peak - TONE_B) <= PEAK_TOLERANCE_HZ,
            "expected ~$TONE_B Hz, got $peak",
        )
    }

    @Test
    fun compressAudio_outOfBoundsIndex_returnsUnsupportedSourceFormat() = runTest {
        val input = buildTwoTrackFixture()
        val output = File(tempDir, "out_oob.m4a")
        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(audioTrackIndex = 5),
        )
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            err is AudioCompressionError.UnsupportedSourceFormat,
            "expected UnsupportedSourceFormat, got $err",
        )
    }

    @Test
    fun audioCompressionConfig_rejectsNegativeIndex() {
        assertFailsWith<IllegalArgumentException> {
            AudioCompressionConfig(audioTrackIndex = -1)
        }
    }

    @Test
    fun probe_reportsAudioTrackCountForMultiTrackInput() = runTest {
        val input = buildTwoTrackFixture()
        val info = kompressor.probe(input.absolutePath).getOrThrow()
        assertEquals(2, info.audioTrackCount)
    }

    @Test
    fun probe_reportsSingleAudioTrackForSingleTrackInput() = runTest {
        val input = File(tempDir, "single-track.mp4")
        co.crackn.kompressor.testutil.AudioInputFixtures.createAacM4a(
            output = input,
            durationSeconds = 1,
        )
        val info = kompressor.probe(input.absolutePath).getOrThrow()
        assertEquals(1, info.audioTrackCount)
    }

    @Test
    fun probe_reportsZeroAudioTracksForVideoOnlyInput() = runTest {
        val input = File(tempDir, "video-only.mp4")
        co.crackn.kompressor.testutil.Mp4Generator.generateMp4(output = input, frameCount = 10)
        val info = kompressor.probe(input.absolutePath).getOrThrow()
        assertEquals(0, info.audioTrackCount)
    }

    private fun buildTwoTrackFixture(): File {
        val file = File(tempDir, "two-track.mp4")
        return MultiTrackAudioFixture.createMultiTrackAudioMp4(
            output = file,
            durationSec = DURATION_SEC,
            trackFrequencies = listOf(TONE_A, TONE_B),
        )
    }

    /**
     * Decode the first audio track of [file] to PCM and return the dominant-frequency peak via
     * a simple Goertzel-style DFT sweep over the candidate bins `[50, 2000] Hz` at 5 Hz
     * resolution. Good enough to pick the expected 440 / 880 Hz bin apart while avoiding a full
     * FFT dependency.
     */
    private fun dominantFrequency(file: File): Int {
        val pcm = decodeToPcm(file)
        val samples = pcm.samples
        val rate = pcm.sampleRate
        val minHz = 50
        val maxHz = 2_000
        val step = 5
        var bestHz = minHz
        var bestMag = -1.0
        var hz = minHz
        while (hz <= maxHz) {
            val mag = goertzelMagnitude(samples, rate, hz.toDouble())
            if (mag > bestMag) {
                bestMag = mag
                bestHz = hz
            }
            hz += step
        }
        return bestHz
    }

    private fun goertzelMagnitude(samples: ShortArray, sampleRate: Int, freq: Double): Double {
        // Standard Goertzel filter — O(N) per frequency bin, no allocations.
        val n = samples.size
        val omega = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until n) {
            q0 = coeff * q1 - q2 + samples[i].toDouble()
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cos(omega)
        val imag = q2 * sin(omega)
        return real * real + imag * imag
    }

    private data class Pcm(val samples: ShortArray, val sampleRate: Int)

    /**
     * Decode the first audio track via [MediaCodec] into interleaved 16-bit PCM. When the
     * encoded track is stereo we average the two channels down to mono because our Goertzel
     * filter operates on a single channel.
     */
    @Suppress("LongMethod")
    private fun decodeToPcm(file: File): Pcm {
        val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }
        check(trackIndex >= 0) { "no audio track in ${file.absolutePath}" }
        val format = extractor.getTrackFormat(trackIndex)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        extractor.selectTrack(trackIndex)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val pcmBytes = ArrayList<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        val timeout = 10_000L

        try {
            while (!outputEos) {
                if (!inputEos) {
                    val idx = decoder.dequeueInputBuffer(timeout)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx) ?: error("decoder input null")
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, timeout)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = decoder.getOutputBuffer(outIdx) ?: error("decoder output null")
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        buf.get(chunk)
                        pcmBytes.add(chunk)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
        val total = pcmBytes.sumOf { it.size }
        val flat = ByteArray(total)
        var offset = 0
        for (c in pcmBytes) {
            System.arraycopy(c, 0, flat, offset, c.size)
            offset += c.size
        }
        val shortBuf = ByteBuffer.wrap(flat).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val interleaved = ShortArray(shortBuf.remaining())
        shortBuf.get(interleaved)
        val mono = if (channels == 1) {
            interleaved
        } else {
            ShortArray(interleaved.size / channels) { i ->
                var sum = 0
                for (ch in 0 until channels) sum += interleaved[i * channels + ch]
                (sum / channels).toShort()
            }
        }
        assertNotNull(mono)
        return Pcm(mono, sampleRate)
    }
}
