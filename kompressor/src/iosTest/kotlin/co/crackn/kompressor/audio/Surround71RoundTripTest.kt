@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.audio

import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readAudioMetadata
import co.crackn.kompressor.testutil.runDeviceOnly
import co.crackn.kompressor.testutil.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * End-to-end 7.1 surround audio round-trip on physical iOS hardware.
 *
 * Skipped on the iOS simulator where the VTAACEncoder gate may fail for multi-channel output.
 */
class Surround71RoundTripTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-surround71-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun sevenPointOneSurround_roundTrip_producesValidOutput() = runDeviceOnly(
        "7.1 multi-channel AAC encoding requires physical iOS hardware",
    ) {
        runTest {
            val inputPath = testDir + "surround71.wav"
            writeBytes(inputPath, WavGenerator.generateWavBytes(DURATION_SEC, SAMPLE_RATE, CHANNELS_71))

            val outputPath = testDir + "out.m4a"
            val result = compressor.compress(
                inputPath = inputPath,
                outputPath = outputPath,
                config = AudioCompressionConfig(
                    channels = AudioChannels.SEVEN_POINT_ONE,
                    bitrate = BITRATE_71,
                    sampleRate = SAMPLE_RATE,
                ),
            )

            assertTrue(result.isSuccess, "7.1 compression failed: ${result.exceptionOrNull()}")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(outputPath), "Output file must exist")
            assertTrue(fileSize(outputPath) > 0, "Output must be non-empty")

            val cr = result.getOrThrow()
            assertTrue(cr.outputSize > 0, "CompressionResult.outputSize must be > 0")
        }
    }

    @Test
    fun sevenPointOneSurround_outputPreservesChannelCount() = runDeviceOnly(
        "7.1 multi-channel AAC encoding requires physical iOS hardware",
    ) {
        runTest {
            val inputPath = testDir + "surround71.wav"
            writeBytes(inputPath, WavGenerator.generateWavBytes(DURATION_SEC, SAMPLE_RATE, CHANNELS_71))

            val outputPath = testDir + "out.m4a"
            compressor.compress(
                inputPath = inputPath,
                outputPath = outputPath,
                config = AudioCompressionConfig(
                    channels = AudioChannels.SEVEN_POINT_ONE,
                    bitrate = BITRATE_71,
                    sampleRate = SAMPLE_RATE,
                ),
            ).getOrThrow()

            val meta = readAudioMetadata(outputPath)
            assertEquals(CHANNELS_71, meta.channels, "Output must preserve 8-channel layout")
        }
    }

    @Test
    fun sevenPointOneSurround_progressIsMonotonic() = runDeviceOnly(
        "7.1 multi-channel AAC encoding requires physical iOS hardware",
    ) {
        runTest {
            val inputPath = testDir + "surround71.wav"
            writeBytes(inputPath, WavGenerator.generateWavBytes(DURATION_SEC, SAMPLE_RATE, CHANNELS_71))

            val progressValues = mutableListOf<Float>()
            compressor.compress(
                inputPath = inputPath,
                outputPath = testDir + "out.m4a",
                config = AudioCompressionConfig(
                    channels = AudioChannels.SEVEN_POINT_ONE,
                    bitrate = BITRATE_71,
                    sampleRate = SAMPLE_RATE,
                ),
                onProgress = { progressValues.add(it) },
            )

            for (i in 1 until progressValues.size) {
                assertTrue(
                    progressValues[i] >= progressValues[i - 1],
                    "Progress must be monotonic: ${progressValues[i - 1]} -> ${progressValues[i]}",
                )
            }
        }
    }

    private companion object {
        const val DURATION_SEC = 2
        const val SAMPLE_RATE = 48_000
        const val CHANNELS_71 = 8
        const val BITRATE_71 = 512_000
    }
}
