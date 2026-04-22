/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.ByteSearch
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.copyResourceToCache
import co.crackn.kompressor.testutil.readAudioDurationMs
import co.crackn.kompressor.testutil.readAudioTrackInfo
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Round-trip coverage for VBR (variable-bitrate) MP3 input — the format LAME encoders emit at
 * `-V 0` / `-V 2` and what most user-generated content in iTunes / Bandcamp / the Archive of
 * Home Audio is shipped as. CBR MP3 is covered by [MultiFormatInputTest]; VBR exercises a
 * distinct Media3 decoder path because the extractor has to read the Xing / Info VBR header
 * from the first frame to compute duration and seek tables rather than dividing file size by a
 * fixed bitrate. A regression there (e.g. `null` probe result for VBR, duration = 0, or silent
 * encoder stall) would never surface in CBR tests.
 *
 * Fixture: `vbr_v0.mp3` — LAME -V 0 encoded 1 s 440 Hz sine, Xing VBR header confirmed at
 * offset 21. Reproducible recipe: `lame -V 0`.
 */
class VbrMp3RoundTripTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-vbr-mp3-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun vbrMp3_compressesToAac() = runTest {
        val input = copyResourceToCache("vbr_v0.mp3", tempDir)
        assertInputIsVbr(input)

        val output = File(tempDir, "from_vbr_v0.m4a")
        val config = AudioCompressionConfig(
            bitrate = 64_000,
            sampleRate = 44_100,
            channels = AudioChannels.MONO,
        )

        val result = compressor.compress(
            MediaSource.Local.FilePath(input.absolutePath),
            MediaDestination.Local.FilePath(output.absolutePath),
            config,
        )

        assertTrue(result.isSuccess, "VBR MP3 compression must succeed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0, "Output must be non-empty")
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")

        val track = readAudioTrackInfo(output)
        assertEquals("audio/mp4a-latm", track.mime, "Output must be AAC")
        assertEquals(config.sampleRate, track.sampleRate, "Output sample rate must match config")
        assertEquals(config.channels.count, track.channels, "Output channel count must match config")

        val durationMs = readAudioDurationMs(output)
        assertTrue(
            abs(durationMs - SOURCE_DURATION_MS) <= DURATION_TOLERANCE_MS,
            "Output duration $durationMs ms must be within ${DURATION_TOLERANCE_MS}ms of the " +
                "${SOURCE_DURATION_MS}ms source — zero or wildly different means the VBR extractor " +
                "failed to produce a populated track",
        )
    }

    /**
     * Sanity-check that the committed fixture still carries the Xing VBR header — if someone
     * regenerates it with CBR flags the assertion catches it before Media3 silently treats it
     * as CBR and masks the VBR-specific decoder path we care about.
     */
    private fun assertInputIsVbr(file: File) {
        val head = readUpTo(file, VBR_HEADER_SCAN_LIMIT)
        val xingIdx = ByteSearch.indexOfAscii(head, "Xing")
        val infoIdx = ByteSearch.indexOfAscii(head, "Info")
        assertTrue(
            xingIdx >= 0,
            "Fixture vbr_v0.mp3 missing Xing header — expected VBR marker, found " +
                "${if (infoIdx >= 0) "CBR 'Info' tag at $infoIdx" else "neither Xing nor Info"}",
        )
    }

    // Portable equivalent of InputStream.readNBytes(int), which is JDK 11 / Android API 33+.
    // Our device-test matrix runs on AWS Device Farm across multiple API levels, so we can't
    // rely on the JDK 11 call here. Reads up to [limit] bytes, stopping early at EOF.
    private fun readUpTo(file: File, limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var total = 0
        file.inputStream().use { stream ->
            while (total < limit) {
                val n = stream.read(buf, total, limit - total)
                if (n < 0) break
                total += n
            }
        }
        return if (total == limit) buf else buf.copyOf(total)
    }

    private companion object {
        const val SOURCE_DURATION_MS = 1_000L

        // LAME / Media3 can inflate decoded duration by ~150 ms on short VBR clips due to
        // encoder-delay + frame padding. Matches MultiFormatInputTest's tolerance.
        const val DURATION_TOLERANCE_MS = 200L

        // The Xing/Info tag lives inside the first audio frame, well within the first 128
        // bytes. 256 gives a comfortable margin without reading the whole file.
        const val VBR_HEADER_SCAN_LIMIT = 256
    }
}
