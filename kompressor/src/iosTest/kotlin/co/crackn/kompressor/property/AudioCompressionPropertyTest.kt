@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.property

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.testutil.OutputValidators
import co.crackn.kompressor.testutil.WavGenerator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
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
        checkAll(
            PropTestConfig(seed = SEED, iterations = ITERATIONS),
            Arb.int(32_000..192_000),
            Arb.element(22_050, 44_100, 48_000),
            Arb.element(AudioChannels.MONO, AudioChannels.STEREO),
        ) { bitrate, sampleRate, channels ->
            val config = AudioCompressionConfig(
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
            )

            val wavBytes = WavGenerator.generateWavBytes(
                durationSeconds = 1,
                sampleRate = INPUT_SAMPLE_RATE,
                channels = INPUT_CHANNELS,
            )
            val inputPath = testDir + "input_${bitrate}_${sampleRate}_${channels.count}.wav"
            val outputPath = testDir + "output_${bitrate}_${sampleRate}_${channels.count}.m4a"
            writeBytes(inputPath, wavBytes)

            val result = compressor.compress(inputPath, outputPath, config)

            assertTrue(result.isSuccess, "Compression failed for config $config: ${result.exceptionOrNull()}")
            val compression = result.getOrThrow()
            assertTrue(compression.outputSize > 0, "Output size should be > 0")
            assertTrue(compression.durationMs >= 0, "Duration should be >= 0")

            val outputBytes = readBytes(outputPath)
            assertTrue(OutputValidators.isValidM4a(outputBytes), "Output should be valid M4A")

            // Clean up between iterations
            NSFileManager.defaultManager.removeItemAtPath(inputPath, null)
            NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        }
    }

    @Test
    fun progressIsMonotonicallyNonDecreasing() = runTest {
        val wavBytes = WavGenerator.generateWavBytes(
            durationSeconds = 2,
            sampleRate = INPUT_SAMPLE_RATE,
            channels = INPUT_CHANNELS,
        )
        val inputPath = testDir + "progress_test.wav"
        val outputPath = testDir + "progress_out.m4a"
        writeBytes(inputPath, wavBytes)
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty(), "Progress should be reported")
        for (i in 1 until progressValues.size) {
            assertTrue(
                progressValues[i] >= progressValues[i - 1],
                "Progress must be non-decreasing: ${progressValues[i - 1]} -> ${progressValues[i]}",
            )
        }
        assertTrue(progressValues.last() >= FINAL_PROGRESS_MIN, "Final progress should reach ~1.0")
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val url = NSURL.fileURLWithPath(path)
        val written = data.writeToURL(url, atomically = true)
        check(written) { "Failed to write file: $path" }
    }

    private fun readBytes(path: String): ByteArray {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?: error("File not found: $path")
        val size = (attrs[NSFileSize] as? Number)?.toInt() ?: error("Cannot read file size: $path")
        val data = platform.Foundation.NSData.dataWithContentsOfFile(path)
            ?: error("Cannot read file: $path")
        return ByteArray(size).also { bytes ->
            bytes.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
    }

    private companion object {
        const val SEED = 12345L
        const val ITERATIONS = 15
        const val INPUT_SAMPLE_RATE = 44_100
        const val INPUT_CHANNELS = 2
        const val FINAL_PROGRESS_MIN = 0.99f
    }
}
