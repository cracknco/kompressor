package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.hasAudioTrack
import co.crackn.kompressor.testutil.readAudioTrackInfo
import co.crackn.kompressor.testutil.readVideoMetadata
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoCompressionError
import co.crackn.kompressor.video.VideoPresets
import co.crackn.kompressor.video.probeVideoShortSide
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidVideoCompressorTest {

    private lateinit var tempDir: File
    private lateinit var inputFile: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-video-test").apply { mkdirs() }
        inputFile = Mp4Generator.generateMp4(
            output = File(tempDir, "input.mp4"),
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            frameCount = INPUT_FRAME_COUNT,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressVideo_producesValidMp4() = runTest {
        val output = File(tempDir, "output.mp4")

        val result = compressor.compress(
            inputPath = inputFile.absolutePath,
            outputPath = output.absolutePath,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val compression = result.getOrThrow()
        assertTrue(output.exists())
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        assertTrue(OutputValidators.isValidMp4(output.readBytes()), "Output should be valid MP4")
    }

    @Test
    fun compressVideo_downscalesTo480p() = runTest {
        val output = File(tempDir, "480p.mp4")
        val config = VideoCompressionConfig(maxResolution = MaxResolution.SD_480)

        val result = compressor.compress(
            inputPath = inputFile.absolutePath,
            outputPath = output.absolutePath,
            config = config,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val metadata = readVideoMetadata(output)
        assertTrue(metadata.width <= MAX_480P_LONG_EDGE, "Width ${metadata.width} exceeds 480p")
        assertTrue(metadata.height <= MAX_480P_SHORT_EDGE, "Height ${metadata.height} exceeds 480p")
    }

    @Test
    fun compressVideo_bitrateAffectsSize() = runTest {
        val outputLow = File(tempDir, "low_bitrate.mp4")
        val outputHigh = File(tempDir, "high_bitrate.mp4")

        val lowResult = compressor.compress(
            inputFile.absolutePath,
            outputLow.absolutePath,
            VideoCompressionConfig(videoBitrate = 200_000),
        )
        val highResult = compressor.compress(
            inputFile.absolutePath,
            outputHigh.absolutePath,
            VideoCompressionConfig(videoBitrate = 3_000_000),
        )

        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)
        assertTrue(
            outputLow.length() < outputHigh.length(),
            "Low bitrate (${outputLow.length()}) should be < high (${outputHigh.length()})",
        )
    }

    @Test
    fun compressVideo_progressReported() = runTest {
        val output = File(tempDir, "progress.mp4")
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputFile.absolutePath,
            outputPath = output.absolutePath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first(), 1e-6f)
        assertEquals(1f, progressValues.last(), 1e-6f)
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1], "Progress should be monotonic")
        }
    }

    @Test
    fun compressVideo_fileNotFound_returnsFailure() = runTest {
        val output = File(tempDir, "out.mp4")
        val result = compressor.compress("/nonexistent/video.mp4", output.absolutePath)
        assertTrue(result.isFailure)
    }

    @Test
    fun probeVideoShortSide_malformedInput_returnsNull() {
        // Direct assertion on the probe's null-fallback: a file with garbage bytes must not
        // crash and must not report a fake dimension. This is the branch
        // `toPresentationOrNull` falls through to when the probe fails, so we test it in
        // isolation rather than inferring from a larger pipeline error.
        val garbage = File(tempDir, "garbage_probe.mp4").apply {
            writeBytes(ByteArray(256) { 0xFF.toByte() })
        }
        val shortSide = probeVideoShortSide(garbage.absolutePath)
        assertEquals(null, shortSide, "Probe must return null on unreadable input, got $shortSide")
    }

    @Test
    fun probeVideoShortSide_nonexistentFile_returnsNull() {
        val shortSide = probeVideoShortSide("/nonexistent/definitely_not_a_video.mp4")
        assertEquals(null, shortSide, "Probe must return null on missing file, got $shortSide")
    }

    @Test
    fun probeVideoShortSide_realVideo_returnsMinDimension() {
        // Sanity check: a real 1280×720 video must produce 720 as the short side. If this
        // regressed (e.g. to `max` or to null) every Presentation decision in the compressor
        // would silently flip to the force-scale path.
        assertEquals(INPUT_HEIGHT, probeVideoShortSide(inputFile.absolutePath))
    }

    @Test
    fun compressVideo_malformedInput_probeFails_gracefulError() = runTest {
        // End-to-end: a malformed MP4 must produce a graceful typed `VideoCompressionError`
        // rather than a crash. Paired with the direct probe tests above, this exercises the
        // full fall-through path: probe returns null → Presentation applied unconditionally
        // → Media3 fails to open → error wrapped + returned.
        val garbage = File(tempDir, "garbage.mp4").apply { writeBytes(ByteArray(256) { 0xFF.toByte() }) }
        val output = File(tempDir, "out_from_garbage.mp4")

        val result = compressor.compress(garbage.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Malformed input must produce a graceful Result.failure, not a crash")
        val err = result.exceptionOrNull()
        assertTrue(
            err is VideoCompressionError,
            "Error must be a typed VideoCompressionError, got ${err?.let { it::class.simpleName }}: $err",
        )
    }

    @Test
    fun compressVideo_videoWithAudio_preservesAudioTrack() = runTest {
        // Regression gate for the "Mp4Generator has no audio track so every video golden is
        // implicitly silent" blind spot. `createMp4WithVideoAndAudio` yields a mixed MP4; the
        // compressor must re-encode both tracks and the output must still carry an audio
        // track at the same MIME (AAC) with matching channel count + sample rate.
        val input = File(tempDir, "v_plus_a_input.mp4")
        AudioInputFixtures.createMp4WithVideoAndAudio(input, durationSeconds = 2)
        val output = File(tempDir, "v_plus_a_output.mp4")

        val result = compressor.compress(input.absolutePath, output.absolutePath)
        assertTrue(result.isSuccess, "V+A compression failed: ${result.exceptionOrNull()}")

        assertTrue(hasAudioTrack(output), "Output MP4 must keep its audio track after video compression")
        val audio = readAudioTrackInfo(output)
        assertTrue(audio.mime.startsWith("audio/mp4a-latm"), "Audio should be AAC, got ${audio.mime}")
        assertEquals(44_100, audio.sampleRate, "Sample rate should be preserved")
        assertEquals(2, audio.channels, "Channel count should be preserved")
    }

    @Test
    fun compressVideo_messagingPreset_producesValidOutput() = runTest {
        val output = File(tempDir, "messaging.mp4")
        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            VideoPresets.MESSAGING,
        )
        assertTrue(result.isSuccess, "Messaging preset failed: ${result.exceptionOrNull()}")
        assertTrue(output.exists())
        assertTrue(OutputValidators.isValidMp4(output.readBytes()))
    }

    @Test
    fun compressVideo_originalResolution_preservesDimensions() = runTest {
        val output = File(tempDir, "original.mp4")
        val config = VideoCompressionConfig(maxResolution = MaxResolution.Original)

        val result = compressor.compress(
            inputFile.absolutePath,
            output.absolutePath,
            config,
        )

        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")
        val metadata = readVideoMetadata(output)
        // Allow +/- 2 for even rounding
        assertTrue(metadata.width in (INPUT_WIDTH - 2)..(INPUT_WIDTH + 2))
        assertTrue(metadata.height in (INPUT_HEIGHT - 2)..(INPUT_HEIGHT + 2))
    }

    private companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        const val INPUT_FRAME_COUNT = 30
        const val MAX_480P_LONG_EDGE = 856
        const val MAX_480P_SHORT_EDGE = 482
    }
}
