package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.copyResourceToCache
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round-trip coverage for the audio input formats the plan explicitly promises support for
 * but that the existing test suite never exercised: MP3, FLAC, OGG/Opus. Previously the
 * non-AAC / non-WAV paths relied entirely on Media3's extractor tests upstream — a
 * regression in how we hand Media3 one of these formats (probe returning null, wrong mime
 * mapping, unexpected channel count) would only surface in user reports.
 *
 * Fixtures are pre-built via ffmpeg (1 s 440 Hz sine, small enough to ship inline):
 *   * `sample_mono_22k.mp3`  — MPEG-1 Layer III, mono, 22.05 kHz, 64 kbps
 *   * `sample_mono_22k.flac` — FLAC mono, 22.05 kHz
 *   * `sample_mono_24k.ogg`  — Opus mono, 24 kHz, 32 kbps
 *
 * AMR-NB is covered separately on-device in `AmrInputTest` because MediaCodec ships an AMR
 * encoder on all Android devices — no binary asset needed.
 */
class MultiFormatInputTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-multi-format-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun mp3Input_compressesToAac() = runTest {
        val input = copyResourceToCache("sample_mono_22k.mp3", tempDir)
        val output = File(tempDir, "from_mp3.m4a")
        val result = compressor.compress(
            input.absolutePath,
            output.absolutePath,
            AudioCompressionConfig(bitrate = 64_000),
        )
        assertTrue(result.isSuccess, "MP3 → AAC must succeed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")
    }

    @Test
    fun flacInput_compressesToAac() = runTest {
        val input = copyResourceToCache("sample_mono_22k.flac", tempDir)
        val output = File(tempDir, "from_flac.m4a")
        val result = compressor.compress(
            input.absolutePath,
            output.absolutePath,
            AudioCompressionConfig(bitrate = 64_000),
        )
        assertTrue(result.isSuccess, "FLAC → AAC must succeed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")
    }

    @Test
    fun oggOpusInput_compressesToAac() = runTest {
        val input = copyResourceToCache("sample_mono_24k.ogg", tempDir)
        val output = File(tempDir, "from_ogg.m4a")
        val result = compressor.compress(
            input.absolutePath,
            output.absolutePath,
            AudioCompressionConfig(bitrate = 64_000),
        )
        assertTrue(result.isSuccess, "OGG/Opus → AAC must succeed: ${result.exceptionOrNull()}")
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output must be valid M4A")
    }
}
