package co.crackn.kompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.testutil.WavGenerator
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On-device coverage for surround (5.1 / 7.1) audio compression on Android.
 *
 * The matrix math is unit-tested in `androidHostTest/SurroundChannelMixingTest`. This file
 * exercises the materialised Media3 pipeline end-to-end on FTL Pixel 6 (which exposes the
 * standard `audio/mp4a-latm` AAC encoder with multi-channel support):
 *
 * - 5.1 source → 5.1 output (passthrough channels, identity matrix).
 * - 5.1 source → stereo output (Media3's constant-power 6→2 downmix).
 * - 7.1 source → stereo output (our hand-rolled BS.775 8→2 matrix).
 * - Stereo source → 5.1 target rejected upfront with typed error (no upmix path).
 */
class SurroundAudioTest {

    private lateinit var testDir: File
    private val compressor = AndroidAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = File.createTempFile("kompressor-surround-", "")
        testDir.delete()
        testDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun fiveOneSource_compressedAsFiveOne_preservesChannelCount() = runTest {
        val input = File(testDir, "5_1.wav")
        input.writeBytes(WavGenerator.generateWavBytes(1, 44_100, channels = 6))
        val output = File(testDir, "5_1_out.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(
                channels = AudioChannels.FIVE_POINT_ONE,
                bitrate = 192_000 * 6 / 2, // ~576 kbps for 5.1 at 44.1kHz
            ),
        )

        assertTrue(result.isSuccess, "5.1 round-trip failed: ${result.exceptionOrNull()}")
        assertEquals(6, readOutputChannelCount(output.absolutePath))
    }

    @Test
    fun fiveOneSource_compressedAsStereo_downmixesCleanly() = runTest {
        val input = File(testDir, "5_1.wav")
        input.writeBytes(WavGenerator.generateWavBytes(1, 44_100, channels = 6))
        val output = File(testDir, "stereo_out.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(channels = AudioChannels.STEREO, bitrate = 128_000),
        )

        assertTrue(result.isSuccess, "5.1→stereo downmix failed: ${result.exceptionOrNull()}")
        assertEquals(2, readOutputChannelCount(output.absolutePath))
    }

    @Test
    fun sevenOneSource_compressedAsStereo_usesHandRolledMatrix() = runTest {
        val input = File(testDir, "7_1.wav")
        input.writeBytes(WavGenerator.generateWavBytes(1, 44_100, channels = 8))
        val output = File(testDir, "stereo_out.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(channels = AudioChannels.STEREO, bitrate = 128_000),
        )

        assertTrue(result.isSuccess, "7.1→stereo downmix failed: ${result.exceptionOrNull()}")
        assertEquals(2, readOutputChannelCount(output.absolutePath))
    }

    @Test
    fun stereoSource_targetingFiveOne_rejectedAsUpmix() = runTest {
        val input = File(testDir, "stereo.wav")
        input.writeBytes(WavGenerator.generateWavBytes(1, 44_100, channels = 2))
        val output = File(testDir, "5_1_out.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(
                channels = AudioChannels.FIVE_POINT_ONE,
                bitrate = 320_000,
            ),
        )

        assertTrue(result.isFailure, "Stereo→5.1 upmix must be rejected")
        val err = result.exceptionOrNull()
        // Either UnsupportedConfiguration (preferred — typed contract) or a Media3 mid-pipeline
        // error mapped to UnsupportedSourceFormat. Both are acceptable typed errors.
        assertTrue(
            err is AudioCompressionError,
            "Expected typed AudioCompressionError, got ${err?.let { it::class.simpleName }}: ${err?.message}",
        )
    }

    private fun readOutputChannelCount(path: String): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            error("No audio track found in $path")
        } finally {
            extractor.release()
        }
    }
}
