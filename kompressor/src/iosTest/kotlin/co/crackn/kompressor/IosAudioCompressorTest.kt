package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAudioCompressorTest {

    private lateinit var testDir: String
    private val compressor = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-audio-test-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun compressAudio_producesValidOutput() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "output.m4a"

        val result = compressor.compress(inputPath, outputPath)

        assertTrue(result.isSuccess)
        val compression = result.getOrThrow()
        assertTrue(compression.outputSize > 0)
        assertTrue(compression.inputSize > 0)
        assertTrue(compression.durationMs >= 0)
    }

    @Test
    fun compressAudio_bitrateAffectsSize() = runTest {
        val inputPath = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputLow = testDir + "low.m4a"
        val outputHigh = testDir + "high.m4a"

        val resultLow = compressor.compress(inputPath, outputLow, AudioCompressionConfig(bitrate = 32_000))
        resultLow.getOrThrow()

        val resultHigh = compressor.compress(inputPath, outputHigh, AudioCompressionConfig(bitrate = 192_000))
        resultHigh.getOrThrow()

        val sizeLow = fileSize(outputLow)
        val sizeHigh = fileSize(outputHigh)
        assertTrue(sizeLow < sizeHigh, "32kbps ($sizeLow) should be < 192kbps ($sizeHigh)")
    }

    @Test
    fun compressAudio_monoReducesSize() = runTest {
        val inputPath = createTestWavFile(3, SAMPLE_RATE_44K, STEREO)
        val outputMono = testDir + "mono.m4a"
        val outputStereo = testDir + "stereo.m4a"
        val config = AudioCompressionConfig(bitrate = 64_000)

        val resultMono = compressor.compress(inputPath, outputMono, config.copy(channels = AudioChannels.MONO))
        resultMono.getOrThrow()

        val resultStereo = compressor.compress(inputPath, outputStereo, config.copy(channels = AudioChannels.STEREO))
        resultStereo.getOrThrow()

        val sizeMono = fileSize(outputMono)
        val sizeStereo = fileSize(outputStereo)
        assertTrue(sizeMono > 0, "Mono output should have positive size")
        assertTrue(sizeStereo > 0, "Stereo output should have positive size")
        assertTrue(sizeMono <= sizeStereo, "mono ($sizeMono) should be <= stereo ($sizeStereo)")
    }

    @Test
    fun compressAudio_fileNotFound_returnsFailure() = runTest {
        val result = compressor.compress("/nonexistent/audio.wav", testDir + "out.m4a")
        assertTrue(result.isFailure)
    }

    @Test
    fun compressAudio_progressReported() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "progress.m4a"
        val progressValues = mutableListOf<Float>()

        compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            onProgress = { progressValues.add(it) },
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first())
        assertEquals(1f, progressValues.last())
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Suppress("SameParameterValue")
    private fun createTestWavFile(durationSeconds: Int, sampleRate: Int, channels: Int): String {
        val totalSamples = sampleRate * durationSeconds
        val dataSize = totalSamples * channels * BYTES_PER_SAMPLE
        val headerSize = WAV_HEADER_SIZE
        val bytes = ByteArray(headerSize + dataSize)

        // RIFF header
        writeString(bytes, 0, "RIFF")
        writeIntLE(bytes, 4, headerSize - RIFF_CHUNK_HEADER + dataSize)
        writeString(bytes, 8, "WAVE")

        // fmt sub-chunk
        writeString(bytes, 12, "fmt ")
        writeIntLE(bytes, 16, PCM_FMT_CHUNK_SIZE)
        writeShortLE(bytes, 20, PCM_FORMAT)
        writeShortLE(bytes, 22, channels)
        writeIntLE(bytes, 24, sampleRate)
        writeIntLE(bytes, 28, sampleRate * channels * BYTES_PER_SAMPLE)
        writeShortLE(bytes, 32, channels * BYTES_PER_SAMPLE)
        writeShortLE(bytes, 34, BITS_PER_SAMPLE)

        // data sub-chunk
        writeString(bytes, 36, "data")
        writeIntLE(bytes, 40, dataSize)

        // PCM sine wave at 440 Hz
        var offset = headerSize
        for (i in 0 until totalSamples) {
            val sample = (Short.MAX_VALUE * sin(2.0 * kotlin.math.PI * TONE_FREQUENCY * i / sampleRate)).toInt().toShort()
            for (ch in 0 until channels) {
                bytes[offset++] = (sample.toInt() and 0xFF).toByte()
                bytes[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }

        val path = testDir + "test_${sampleRate}hz_${channels}ch_${durationSeconds}s.wav"
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val url = NSURL.fileURLWithPath(path)
        data.writeToURL(url, atomically = true)
        return path
    }

    private fun writeString(bytes: ByteArray, offset: Int, value: String) {
        for (i in value.indices) {
            bytes[offset + i] = value[i].code.toByte()
        }
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, null) ?: error("File not found: $path")
        return (attrs[NSFileSize] as? Number)?.toLong()
            ?: error("Cannot read file size: $path")
    }

    private companion object {
        const val SAMPLE_RATE_44K = 44_100
        const val STEREO = 2
        const val BYTES_PER_SAMPLE = 2
        const val BITS_PER_SAMPLE = 16
        const val PCM_FORMAT = 1
        const val PCM_FMT_CHUNK_SIZE = 16
        const val WAV_HEADER_SIZE = 44
        const val RIFF_CHUNK_HEADER = 8
        const val TONE_FREQUENCY = 440.0
    }
}