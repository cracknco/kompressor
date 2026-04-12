@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.property

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalKotest::class)
class AudioCompressionPropertyTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-prop-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun randomValidConfigs_alwaysSucceed() = runTest {
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
        )
        checkAll(
            PropTestConfig(seed = SEED, iterations = ITERATIONS),
            // Exercise the full public [AudioCompressionConfig] range so regressions in iOS AAC
            // handling at the bitrate/sample-rate extremes surface here rather than in user
            // reports. Source WAV is 44.1 kHz stereo; mono target is a valid down-mix iOS
            // honours, stereo → stereo passes straight through.
            Arb.int(32_000..256_000),
            Arb.element(22_050, 32_000, 44_100, 48_000),
            Arb.element(AudioChannels.MONO, AudioChannels.STEREO),
        ) { bitrate, sampleRate, channels ->
            val config = AudioCompressionConfig(
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
            )

            val inputPath = testDir + "input_${bitrate}_${sampleRate}_${channels.count}.wav"
            val outputPath = testDir + "output_${bitrate}_${sampleRate}_${channels.count}.m4a"
            writeBytes(inputPath, wavBytes)

            val result = compressor.compress(inputPath, outputPath, config)

            // Either the compressor succeeds and produces a valid M4A, or it rejects the
            // configuration with a typed `UnsupportedConfiguration` — both are well-defined
            // library outcomes. Any other failure mode (opaque `Exception`, crash, silent
            // wrong-format output) is a regression worth catching.
            val error = result.exceptionOrNull()
            if (result.isSuccess) {
                val compression = result.getOrThrow()
                assertTrue(compression.outputSize > 0, "Output size should be > 0 for $config")
                assertTrue(compression.durationMs >= 0, "Duration should be >= 0 for $config")
                assertTrue(
                    OutputValidators.isValidM4a(readBytes(outputPath)),
                    "Output should be valid M4A for $config",
                )
            } else {
                assertTrue(
                    error is AudioCompressionError.UnsupportedConfiguration,
                    "Compression failed for $config with unexpected error: $error",
                )
            }

            NSFileManager.defaultManager.removeItemAtPath(inputPath, null)
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    @Test
    fun smokeSet_knownGoodConfigs_allSucceed() = runTest {
        // Hard guard against the "all-rejected vacuous pass" failure mode: if a future
        // regression pushed every random config into `UnsupportedConfiguration`, the
        // property test above would happily report "all 15 outcomes acceptable" without
        // ever exercising the success path. This smoke set pins the most common real-world
        // configurations — if these stop working, a real bug has been introduced.
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
        )
        val smokeConfigs = listOf(
            "default" to AudioCompressionConfig(),
            "high-quality" to AudioPresets.HIGH_QUALITY,
            "voice-message" to AudioPresets.VOICE_MESSAGE,
            "stereo-128k-44.1" to AudioCompressionConfig(
                bitrate = 128_000,
                sampleRate = 44_100,
                channels = AudioChannels.STEREO,
            ),
            "mono-64k-22k" to AudioCompressionConfig(
                bitrate = 64_000,
                sampleRate = 22_050,
                channels = AudioChannels.MONO,
            ),
        )

        for ((label, config) in smokeConfigs) {
            val inputPath = testDir + "smoke_input_$label.wav"
            val outputPath = testDir + "smoke_output_$label.m4a"
            writeBytes(inputPath, wavBytes)

            val result = compressor.compress(inputPath, outputPath, config)

            assertTrue(
                result.isSuccess,
                "Smoke config '$label' ($config) must succeed end-to-end, got: ${result.exceptionOrNull()}",
            )

            NSFileManager.defaultManager.removeItemAtPath(inputPath, null)
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    @Test
    fun progressIsMonotonicallyNonDecreasing() = runTest {
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 2,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
        )
        val inputPath = testDir + "progress_test.wav"
        val outputPath = testDir + "progress_out.m4a"
        writeBytes(inputPath, wavBytes)
        val progressValues = mutableListOf<Float>()

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            onProgress = { progressValues.add(it) },
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")

        assertTrue(progressValues.isNotEmpty(), "Progress should be reported")
        for (i in 1 until progressValues.size) {
            assertTrue(
                progressValues[i] >= progressValues[i - 1],
                "Progress must be non-decreasing: ${progressValues[i - 1]} -> ${progressValues[i]}",
            )
        }
        assertTrue(progressValues.last() >= FINAL_PROGRESS_MIN, "Final progress should reach ~1.0")
    }

    private companion object {
        const val SEED = 12345L
        const val ITERATIONS = 15
        const val FINAL_PROGRESS_MIN = 0.99f
    }
}
