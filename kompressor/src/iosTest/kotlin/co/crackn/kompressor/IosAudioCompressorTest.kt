package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
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

        val lowResult = compressor.compress(inputPath, outputLow, AudioCompressionConfig(bitrate = 32_000))
        val highResult = compressor.compress(inputPath, outputHigh, AudioCompressionConfig(bitrate = 192_000))
        assertTrue(lowResult.isSuccess)
        assertTrue(highResult.isSuccess)

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

        val monoResult = compressor.compress(inputPath, outputMono, config.copy(channels = AudioChannels.MONO))
        val stereoResult = compressor.compress(inputPath, outputStereo, config.copy(channels = AudioChannels.STEREO))
        assertTrue(monoResult.isSuccess)
        assertTrue(stereoResult.isSuccess)

        val sizeMono = fileSize(outputMono)
        val sizeStereo = fileSize(outputStereo)
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

    @Test
    fun compressAudio_48kTo44k_producesValidOutput() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_48K, STEREO)
        val outputPath = testDir + "48k_to_44k.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_44K),
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that resampling actually occurred
        val metadata = readOutputMetadata(outputPath)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should be resampled to 44.1kHz")
        assertEquals(STEREO, metadata.channels, "Output should maintain stereo channels")
    }

    @Test
    fun compressAudio_44kTo22k_voiceMessage() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "voice_message.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioPresets.VOICE_MESSAGE,
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that resampling and channel conversion occurred for voice preset
        val metadata = readOutputMetadata(outputPath)
        assertEquals(SAMPLE_RATE_22K, metadata.sampleRate, "Voice message should be resampled to 22.05kHz")
        assertEquals(MONO, metadata.channels, "Voice message should be converted to mono")
    }

    @Test
    fun compressAudio_stereoToMono_sameSampleRate() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, STEREO)
        val outputPath = testDir + "stereo_to_mono.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(channels = AudioChannels.MONO),
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that channel conversion actually occurred
        val metadata = readOutputMetadata(outputPath)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should maintain 44.1kHz sample rate")
        assertEquals(MONO, metadata.channels, "Output should be converted to mono")
    }

    @Test
    fun compressAudio_monoToStereo_sameSampleRate() = runTest {
        val inputPath = createTestWavFile(2, SAMPLE_RATE_44K, MONO)
        val outputPath = testDir + "mono_to_stereo.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(channels = AudioChannels.STEREO),
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().outputSize > 0)

        // Assert that channel conversion actually occurred
        val metadata = readOutputMetadata(outputPath)
        assertEquals(SAMPLE_RATE_44K, metadata.sampleRate, "Output should maintain 44.1kHz sample rate")
        assertEquals(STEREO, metadata.channels, "Output should be converted to stereo")
    }

    @Test
    fun compressAudio_sampleRateConversion_preservesDuration() = runTest {
        val durationSec = 2
        val inputPath = createTestWavFile(durationSec, SAMPLE_RATE_48K, STEREO)
        val outputPath = testDir + "duration_check.m4a"

        val result = compressor.compress(
            inputPath = inputPath,
            outputPath = outputPath,
            config = AudioCompressionConfig(sampleRate = SAMPLE_RATE_22K),
        )
        assertTrue(result.isSuccess, "Compression failed: ${result.exceptionOrNull()}")

        val outputDurationSec = readOutputDurationSec(outputPath)
        assertTrue(
            kotlin.math.abs(outputDurationSec - durationSec.toDouble()) < DURATION_TOLERANCE_SEC,
            "Output duration ${outputDurationSec}s should be within " +
                "${DURATION_TOLERANCE_SEC}s of ${durationSec}s",
        )

        // Assert that resampling actually occurred
        val metadata = readOutputMetadata(outputPath)
        assertEquals(SAMPLE_RATE_22K, metadata.sampleRate, "Output should be resampled to 22.05kHz")
        assertEquals(STEREO, metadata.channels, "Output should maintain stereo channels")
    }

    private fun readOutputDurationSec(path: String): Double {
        val asset = platform.AVFoundation.AVURLAsset(
            uRL = NSURL.fileURLWithPath(path), options = null,
        )
        return platform.CoreMedia.CMTimeGetSeconds(asset.duration)
    }

    private fun readOutputMetadata(path: String): AudioMetadata {
        val asset = platform.AVFoundation.AVURLAsset(
            uRL = NSURL.fileURLWithPath(path), options = null,
        )
        val tracks = asset.tracksWithMediaType(platform.AVFoundation.AVMediaTypeAudio)
        check(tracks.isNotEmpty()) { "No audio track found in output" }

        val track = tracks.first() as platform.AVFoundation.AVAssetTrack
        val formatDescriptions = track.formatDescriptions as List<*>
        check(formatDescriptions.isNotEmpty()) { "No format descriptions found" }

        val formatDesc = formatDescriptions.first()
        val basicDesc = platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription(
            formatDesc as platform.CoreMedia.CMAudioFormatDescriptionRef
        )
        checkNotNull(basicDesc) { "Could not read audio format description" }

        val sampleRate = basicDesc.pointed.mSampleRate.toInt()
        val channels = basicDesc.pointed.mChannelsPerFrame.toInt()

        return AudioMetadata(sampleRate, channels)
    }

    private data class AudioMetadata(val sampleRate: Int, val channels: Int)

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

        // PCM sine waves - distinct frequencies for each channel to verify mixing
        var offset = headerSize
        for (i in 0 until totalSamples) {
            for (ch in 0 until channels) {
                // Use different frequencies for different channels:
                // Left channel (0): 440 Hz, Right channel (1): 880 Hz
                val frequency = TONE_FREQUENCY * (ch + 1)
                val sample = (Short.MAX_VALUE * sin(2.0 * kotlin.math.PI * frequency * i / sampleRate)).toInt().toShort()
                bytes[offset++] = (sample.toInt() and 0xFF).toByte()
                bytes[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }

        val path = testDir + "test_${sampleRate}hz_${channels}ch_${durationSeconds}s.wav"
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val url = NSURL.fileURLWithPath(path)
        val written = data.writeToURL(url, atomically = true)
        check(written) { "Failed to write test WAV file: $path" }
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
        const val SAMPLE_RATE_48K = 48_000
        const val SAMPLE_RATE_22K = 22_050
        const val STEREO = 2
        const val MONO = 1
        const val BYTES_PER_SAMPLE = 2
        const val BITS_PER_SAMPLE = 16
        const val PCM_FORMAT = 1
        const val PCM_FMT_CHUNK_SIZE = 16
        const val WAV_HEADER_SIZE = 44
        const val RIFF_CHUNK_HEADER = 8
        const val TONE_FREQUENCY = 440.0
        const val DURATION_TOLERANCE_SEC = 0.3
    }
}