package co.crackn.kompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.video.AndroidVideoCompressor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end verification that [queryDeviceCapabilities] doesn't lie: the codec the
 * platform actually selects to encode a Kompressor output is one of the encoder
 * [CodecSupport.codecName]s the capability matrix advertises for that mime type.
 *
 * Strategy:
 *  1. Run a real `compress()` and read back the output's `MediaFormat` via [MediaExtractor].
 *  2. Confirm the mime is the expected one (`video/avc` for video, `audio/mp4a-latm` for audio).
 *  3. Build the encoder-shaped format from the output's properties and ask
 *     [android.media.MediaCodecList.findEncoderForFormat] which encoder name the platform
 *     would pick for that exact format.
 *  4. Assert that name appears in the capability matrix for the corresponding mime+role.
 */
class CodecNameVerificationTest {

    private lateinit var tempDir: File
    private val videoCompressor = AndroidVideoCompressor()
    private val audioCompressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-codec-name-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun videoCompressor_outputEncoderIsListedInCapabilities() = runTest {
        val input = Mp4Generator.generateMp4(
            output = File(tempDir, "input.mp4"),
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            frameCount = VIDEO_FRAMES,
        )
        val output = File(tempDir, "out.mp4")

        val result = videoCompressor.compress(input.absolutePath, output.absolutePath)
        assertTrue(result.isSuccess, "video compress failed: ${result.exceptionOrNull()}")

        val format = readTrackFormat(output, mimePrefix = "video/")
        val mime = format.getString(MediaFormat.KEY_MIME)
        assertEquals(VIDEO_AVC_MIME, mime, "Kompressor output should be H.264")

        // Reconstruct the encoder format the platform would have used. Width/height/frame-rate
        // come from the actual output; bitrate is set to a conservative non-zero value because
        // findEncoderForFormat ignores bitrate when matching but does require the field present
        // for video formats on some OEM stacks.
        val encoderFormat = MediaFormat.createVideoFormat(
            VIDEO_AVC_MIME,
            format.getInteger(MediaFormat.KEY_WIDTH),
            format.getInteger(MediaFormat.KEY_HEIGHT),
        ).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, FALLBACK_FRAME_RATE)
            setInteger(MediaFormat.KEY_BIT_RATE, FALLBACK_VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE)
        }
        assertEncoderNameAdvertised(encoderFormat, VIDEO_AVC_MIME)
    }

    @Test
    fun audioCompressor_outputEncoderIsListedInCapabilities() = runTest {
        val input = createWav(File(tempDir, "input.wav"))
        val output = File(tempDir, "out.m4a")

        val result = audioCompressor.compress(input.absolutePath, output.absolutePath)
        assertTrue(result.isSuccess, "audio compress failed: ${result.exceptionOrNull()}")

        val format = readTrackFormat(output, mimePrefix = "audio/")
        val mime = format.getString(MediaFormat.KEY_MIME)
        assertEquals(AUDIO_AAC_MIME, mime, "Kompressor audio output should be AAC")

        val encoderFormat = MediaFormat.createAudioFormat(
            AUDIO_AAC_MIME,
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, FALLBACK_AUDIO_BITRATE)
        }
        assertEncoderNameAdvertised(encoderFormat, AUDIO_AAC_MIME)
    }

    private fun assertEncoderNameAdvertised(encoderFormat: MediaFormat, mime: String) {
        val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val selectedEncoder = codecList.findEncoderForFormat(encoderFormat)
        assertNotNull(
            selectedEncoder,
            "Platform reports no encoder for $encoderFormat — this device cannot have produced the output",
        )

        val caps = queryDeviceCapabilities()
        val list = if (mime.startsWith("video/")) caps.video else caps.audio
        val advertisedNames = list
            .filter { it.mimeType == mime && it.role == CodecSupport.Role.Encoder }
            .mapNotNull { it.codecName }
            .toSet()
        assertTrue(
            selectedEncoder in advertisedNames,
            "Selected encoder '$selectedEncoder' for $mime is not in advertised " +
                "encoder names $advertisedNames — capability matrix is out of sync with reality",
        )
    }

    private fun readTrackFormat(file: File, mimePrefix: String): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(mimePrefix)) return format
            }
            error("No $mimePrefix track in ${file.absolutePath}")
        } finally {
            extractor.release()
        }
    }

    private fun createWav(target: File): File {
        val bytes = WavGenerator.generateWavBytes(
            durationSeconds = AUDIO_DURATION_SECONDS,
            sampleRate = AUDIO_SAMPLE_RATE,
            channels = AUDIO_CHANNELS,
        )
        target.writeBytes(bytes)
        return target
    }

    private companion object {
        const val VIDEO_AVC_MIME = "video/avc"
        const val AUDIO_AAC_MIME = "audio/mp4a-latm"
        const val VIDEO_WIDTH = 320
        const val VIDEO_HEIGHT = 240
        const val VIDEO_FRAMES = 30
        const val FALLBACK_FRAME_RATE = 30
        const val FALLBACK_VIDEO_BITRATE = 500_000
        const val FALLBACK_AUDIO_BITRATE = 128_000
        const val AUDIO_DURATION_SECONDS = 2
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_CHANNELS = 2

        // MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface — Surface color format constant
        // declared inline because the real symbol lives in `android.media`.
        const val COLOR_FORMAT_SURFACE = 0x7F000789
    }
}
