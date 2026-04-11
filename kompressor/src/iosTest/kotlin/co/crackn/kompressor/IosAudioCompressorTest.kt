package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.testutil.TestConstants.MONO
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_22K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_48K
import co.crackn.kompressor.testutil.TestConstants.DURATION_TOLERANCE_SEC
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
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
        val bytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val path = testDir + "test_${sampleRate}hz_${channels}ch_${durationSeconds}s.wav"
        writeBytes(path, bytes)
        return path
    }

}