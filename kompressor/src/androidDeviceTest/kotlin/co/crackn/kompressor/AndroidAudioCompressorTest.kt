package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.SlowAudioProcessor
import co.crackn.kompressor.testutil.TestConstants.DURATION_TOLERANCE_MS
import co.crackn.kompressor.testutil.TestConstants.MONO
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_22K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_48K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.hasVideoTrack
import co.crackn.kompressor.testutil.readAudioDurationMs
import co.crackn.kompressor.testutil.readAudioMetadata
import co.crackn.kompressor.testutil.readAudioTrackInfo
import co.crackn.kompressor.testutil.readContainerBitrate
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidAudioCompressorTest {

    private lateinit var tempDir: File
    private val compressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-audio-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun compressAudio_producesValidOutput() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "output.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
        )

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(output.exists())
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Output should be valid M4A")
    }

    @Test
    fun compressAudio_bitrateAffectsSize() = runTest {
        val input = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputLow = File(tempDir, "low.m4a")
        val outputHigh = File(tempDir, "high.m4a")

        val lowResult = compressor.compress(
            input.absolutePath,
            outputLow.absolutePath,
            AudioCompressionConfig(bitrate = 32_000),
        )
        val highResult = compressor.compress(
            input.absolutePath,
            outputHigh.absolutePath,
            AudioCompressionConfig(bitrate = 192_000),
        )
        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)
        assertTrue(outputLow.exists())
        assertTrue(outputHigh.exists())

        assertTrue(
            outputLow.length() < outputHigh.length(),
            "32kbps (${outputLow.length()}) should be < 192kbps (${outputHigh.length()})",
        )
    }

    @Test
    fun compressAudio_monoChannelCountIsHonoured() = runTest {
        // AAC at a fixed bitrate produces roughly the same file size regardless of channel count,
        // so comparing mono vs stereo byte sizes at equal bitrate is not a reliable signal.
        // Instead verify the functional contract: the output carries the configured channel
        // count, which is what callers actually observe.
        val input = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputMono = File(tempDir, "mono.m4a")
        val outputStereo = File(tempDir, "stereo.m4a")
        val config = AudioCompressionConfig(bitrate = 64_000)

        val monoResult = compressor.compress(
            input.absolutePath,
            outputMono.absolutePath,
            config.copy(channels = AudioChannels.MONO),
        )
        val stereoResult = compressor.compress(
            input.absolutePath,
            outputStereo.absolutePath,
            config.copy(channels = AudioChannels.STEREO),
        )
        assertTrue(monoResult.isSuccess, "mono compression failed: ${monoResult.exceptionOrNull()}")
        assertTrue(stereoResult.isSuccess, "stereo compression failed: ${stereoResult.exceptionOrNull()}")

        assertEquals(MONO, readAudioMetadata(outputMono).channels, "Output should be mono when configured")
        assertEquals(STEREO, readAudioMetadata(outputStereo).channels, "Output should be stereo when configured")
    }

    @Test
    fun compressAudio_fileNotFound_returnsFailure() = runTest {
        val output = File(tempDir, "out.m4a")
        val result = compressor.compress("/nonexistent/audio.wav", output.absolutePath)
        assertTrue(result.isFailure)
    }

    @Test
    fun compressAudio_progressReported() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "progress.m4a")
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first(), 1e-6f)
        assertEquals(1f, progressValues.last(), 1e-6f)
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun compressAudio_48kTo44k_producesValidOutput() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_48K, STEREO)
        val output = File(tempDir, "48k_to_44k.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_44K),
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that resampling actually occurred
        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should be resampled to 44.1kHz")
        assertEquals(STEREO, metadata.channels, "Output should maintain stereo channels")
    }

    @Test
    fun compressAudio_44kTo22k_voiceMessage() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "voice_message.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioPresets.VOICE_MESSAGE,
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that resampling and channel conversion occurred for voice preset
        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_22K, metadata.sampleRate, "Voice message should be resampled to 22.05kHz")
        assertEquals(MONO, metadata.channels, "Voice message should be converted to mono")
    }

    @Test
    fun compressAudio_stereoToMono_sameSampleRate() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "stereo_to_mono.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(channels = AudioChannels.MONO),
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that channel conversion actually occurred
        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should maintain 44.1kHz sample rate")
        assertEquals(MONO, metadata.channels, "Output should be converted to mono")
    }

    @Test
    fun compressAudio_monoToStereo_sameSampleRate() = runTest {
        val input = createTestWavFile(2, SAMPLE_RATE_44K, MONO)
        val output = File(tempDir, "mono_to_stereo.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(channels = AudioChannels.STEREO),
        )

        assertTrue(result.isSuccess)
        assertTrue(output.exists())
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that channel conversion actually occurred
        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should maintain 44.1kHz sample rate")
        assertEquals(STEREO, metadata.channels, "Output should be converted to stereo")
    }

    @Test
    fun compressAudio_sampleRateConversion_preservesDuration() = runTest {
        val durationSec = 2
        val input = createTestWavFile(durationSec, SAMPLE_RATE_48K, STEREO)
        val output = File(tempDir, "duration_check.m4a")

        val result = compressor.compress(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_22K),
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")

        val outputDurationMs = readAudioDurationMs(output)
        val expectedMs = durationSec * MS_PER_SECOND
        assertTrue(
            abs(outputDurationMs - expectedMs) < DURATION_TOLERANCE_MS,
            "Output duration $outputDurationMs ms should be within " +
                "${DURATION_TOLERANCE_MS}ms of ${expectedMs}ms",
        )

        // Assert that resampling actually occurred
        val metadata = readAudioMetadata(output)
        assertEquals(SAMPLE_RATE_22K, metadata.sampleRate, "Output should be resampled to 22.05kHz")
        assertEquals(STEREO, metadata.channels, "Output should maintain stereo channels")
    }

    @Test
    fun aacInput_matchingConfig_producesValidOutput() = runTest {
        val input = File(tempDir, "input_aac.m4a")
        AudioInputFixtures.createAacM4a(
            input,
            durationSeconds = 2,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
            bitrate = 128_000,
        )
        val output = File(tempDir, "aac_passthrough.m4a")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "AAC → AAC should succeed: ${result.exceptionOrNull()}")
        assertTrue(output.exists())
        assertTrue(OutputValidators.isValidM4a(output.readBytes()), "Passthrough output must be valid M4A")
        val meta = readAudioTrackInfo(output)
        assertEquals(SAMPLE_RATE_44K, meta.sampleRate)
        assertEquals(STEREO, meta.channels)
    }

    @Test
    fun mp4WithVideoAndAudio_producesAudioOnlyOutput() = runTest {
        val input = File(tempDir, "input_mixed.mp4")
        AudioInputFixtures.createMp4WithVideoAndAudio(
            input,
            durationSeconds = 2,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
        )
        val output = File(tempDir, "audio_only.m4a")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "MP4(A+V) → M4A should succeed: ${result.exceptionOrNull()}")
        assertTrue(output.exists())
        assertTrue(OutputValidators.isValidM4a(output.readBytes()))
        assertTrue(!hasVideoTrack(output), "Output must not contain a video track")
        val meta = readAudioTrackInfo(output)
        assertTrue(meta.mime.startsWith("audio/"))
    }

    @Test
    fun cancellation_deletesPartialOutput() = runBlocking {
        // Inject a [SlowAudioProcessor] into the Media3 Effects chain so the encoder
        // deterministically takes longer than the cancel delay, regardless of device speed.
        // Without this, fast hardware (Samsung A53, future devices) can finish the entire
        // transcode before the cancel handshake lands, leaving the output file in place and
        // making the cleanup assertion silently vacuous.
        val slowCompressor = AndroidAudioCompressor(
            testExtraAudioProcessors = listOf(SlowAudioProcessor(SLOW_PROCESSOR_DELAY_MS)),
        )
        val input = createTestWavFile(CANCELLATION_INPUT_SECONDS, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "cancelled.m4a")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val job = scope.launch {
            slowCompressor.compress(inputPath = input.absolutePath, outputPath = output.absolutePath)
        }

        // The SlowAudioProcessor guarantees the encoder can't finish in < 1 second, so a 200 ms
        // delay reliably places the cancel mid-export on every device we care about.
        delay(CANCELLATION_START_DELAY_MS)
        job.cancel()
        withTimeout(CANCELLATION_TIMEOUT_MS) { job.join() }

        assertTrue(job.isCancelled, "Cancel must have interrupted the export, not completed normally")
        assertTrue(
            !output.exists(),
            "Cancelled export must delete its partial output, got ${output.length()} bytes",
        )
    }

    @Test
    fun bitrateRespected_withinTolerance() = runTest {
        val input = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val output = File(tempDir, "bitrate_check.m4a")
        val target = 128_000

        val result = compressor.compress(
            input.absolutePath,
            output.absolutePath,
            AudioCompressionConfig(bitrate = target),
        )
        assertTrue(result.isSuccess)

        val observed: Int = assertNotNull(readContainerBitrate(output), "Output container should report a bitrate")
        // Encoder tolerance is platform-dependent; allow ±50% around the request for robustness.
        val low = (target * 0.5).toInt()
        val high = (target * 1.5).toInt()
        assertTrue(
            observed in low..high,
            "Observed bitrate $observed should be within [$low, $high] for target $target",
        )
    }

    @Test
    fun supportedInputFormats_containsCommonAudioMimes() {
        val formats = compressor.supportedInputFormats
        // At least one common audio MIME should be advertised. Devices vary, so we accept any.
        val expected = setOf("audio/mp4a-latm", "audio/mpeg", "audio/flac", "audio/raw")
        assertTrue(
            formats.any { it in expected },
            "Expected at least one of $expected in $formats",
        )
    }

    @Test
    fun supportedOutputFormats_containsAac() {
        assertTrue(
            "audio/mp4a-latm" in compressor.supportedOutputFormats,
            "AAC must be a supported output: ${compressor.supportedOutputFormats}",
        )
    }

    @Suppress("SameParameterValue")
    private fun createTestWavFile(durationSeconds: Int, sampleRate: Int, channels: Int): File {
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val file = File(tempDir, "test_${sampleRate}hz_${channels}ch_${durationSeconds}s.wav")
        file.writeBytes(bytes)
        return file
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L

        // Input size matters less than the [SlowAudioProcessor] stall in
        // `cancellation_deletesPartialOutput` — 5 s is ample to trigger several encoder cycles,
        // and keeps WAV generation fast on the emulator.
        const val CANCELLATION_INPUT_SECONDS = 5

        // Delay per sample-buffer the injected [SlowAudioProcessor] stalls the audio pipeline.
        // Media3 typically queues ~20 buffers/second at 44.1 kHz stereo, so 50 ms/buffer adds
        // ~1 s wall time per second of audio — trivially outlasts the 200 ms cancel delay.
        const val SLOW_PROCESSOR_DELAY_MS = 50L
        const val CANCELLATION_START_DELAY_MS = 200L
        const val CANCELLATION_TIMEOUT_MS = 15_000L
    }
}