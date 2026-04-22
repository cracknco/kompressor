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
import co.crackn.kompressor.testutil.readAudioTrackInfo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Guarantees the audio compressor does not carry the FLAC `PICTURE` metadata block (embedded
 * cover art) into the AAC/M4A output.
 *
 * **Why this matters.** FLAC's `METADATA_BLOCK_PICTURE` (type 6) can embed arbitrary image data
 * — and the media libraries consumers of Kompressor use (cloud upload pipelines, CDN image
 * scanners) treat audio files as bytes-on-the-wire. A 10 MB cover-art blob tagged onto a 30 KB
 * AAC file defeats the entire "compress before upload" contract. This test locks in that
 * `AndroidAudioCompressor` + Media3's AAC encoder produce a container whose bytes contain
 * neither the original PNG cover-art bytes nor any FLAC / ID3 picture-metadata format
 * identifier.
 *
 * Fixture: `with_cover_art.flac` — FLAC stream with a PICTURE block whose payload is a 32x32
 * PNG. Reproducible recipe: `flac --picture`.
 */
class FlacCoverArtDroppedTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-flac-cover-art-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun coverArtIsNotCarriedIntoOutput() = runTest {
        val input = copyResourceToCache("with_cover_art.flac", tempDir)
        val inputBytes = input.readBytes()
        assertInputHasPictureBlock(inputBytes)

        val output = File(tempDir, "from_with_cover_art.m4a")
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
        assertTrue(result.isSuccess, "FLAC→AAC must succeed: ${result.exceptionOrNull()}")

        val outputBytes = output.readBytes()
        assertTrue(OutputValidators.isValidM4a(outputBytes), "Output must be valid M4A")

        val track = readAudioTrackInfo(output)
        assertEquals("audio/mp4a-latm", track.mime, "Output must be AAC (cover art is audio-only)")

        // Byte-level negative assertions — these are the guardrails the DoD asks for. The
        // cover art is a PNG whose signature is `89 50 4E 47 0D 0A 1A 0A`. Any remnant in the
        // output means the transformer silently muxed the cover art into the MP4 `moov/udta`
        // atom (or worse, into the mdat payload). An AAC-only M4A never contains PNG bytes.
        assertDoesNotContain(outputBytes, PNG_SIGNATURE, "PNG signature (cover art leakage)")

        // FLAC picture blocks don't have an ASCII marker, but the Android MP4 muxer's
        // iTunes-metadata path writes `covr` (cover art atom). Assert it isn't there — if
        // Media3 ever starts forwarding FLAC pictures into `covr` atoms this test flags it.
        assertDoesNotContain(outputBytes, COVR_ATOM, "iTunes 'covr' cover-art atom")

        // ID3v2's picture frame tag — not expected in MP4 but if a future transformer
        // path ever prepends an ID3v2 wrapper this catches it.
        assertDoesNotContain(outputBytes, APIC_MARKER, "ID3v2 APIC picture frame")
    }

    private fun assertInputHasPictureBlock(bytes: ByteArray) {
        // fLaC signature is at bytes 0..3 of every FLAC stream. The METADATA_BLOCK_HEADER is a
        // 4-byte big-endian record: the first byte encodes `is_last` (high bit) + `block_type`
        // (low 7 bits). Block type 6 = PICTURE. We scan the header chain (each block's length
        // is its low 24 bits) until we hit one whose type is 6, or run out. This pins the
        // fixture's cover-art metadata so a future regeneration that forgets `--picture` fails
        // loudly here rather than silently masking a compressor-side regression.
        val flacMagic = "fLaC".encodeToByteArray()
        check(bytes.size >= flacMagic.size && bytes.copyOfRange(0, flacMagic.size).contentEquals(flacMagic)) {
            "Fixture with_cover_art.flac is not a FLAC stream (missing fLaC magic)"
        }
        var offset = flacMagic.size
        var foundPicture = false
        while (offset + FLAC_META_HEADER_SIZE <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            if (blockType == FLAC_PICTURE_BLOCK) {
                foundPicture = true
                break
            }
            offset += FLAC_META_HEADER_SIZE + length
            if (isLast) break
        }
        assertTrue(
            foundPicture,
            "Fixture with_cover_art.flac must carry a METADATA_BLOCK_PICTURE (type 6) — " +
                "otherwise the compressor-side drop assertion is testing nothing",
        )
    }

    private fun assertDoesNotContain(haystack: ByteArray, needle: ByteArray, description: String) {
        val idx = ByteSearch.indexOf(haystack, needle)
        assertTrue(
            idx < 0,
            "$description must not appear in M4A output, but was found at offset $idx",
        )
    }

    private companion object {
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val COVR_ATOM: ByteArray = "covr".encodeToByteArray()
        val APIC_MARKER: ByteArray = "APIC".encodeToByteArray()

        const val FLAC_META_HEADER_SIZE = 4
        const val FLAC_PICTURE_BLOCK = 6
    }
}
