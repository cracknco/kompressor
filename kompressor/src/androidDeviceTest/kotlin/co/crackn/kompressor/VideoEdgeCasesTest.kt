package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.hasAudioTrack
import co.crackn.kompressor.testutil.hasVideoTrack
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Edge-case inputs for the video compressor: audio-only MP4s (must be rejected upfront with
 * `VideoCompressionError.UnsupportedSourceFormat`) and video-only MP4s (must compress
 * successfully, producing a video-only output). The library pre-flight that rejects audio-only
 * inputs lives in `AndroidVideoCompressor.probeVideoTracks`.
 */
class VideoEdgeCasesTest {

    private lateinit var tempDir: File
    private val compressor = AndroidVideoCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-video-edge-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun audioOnlyMp4_rejectedWithUnsupportedSourceFormat() = runTest {
        val input = File(tempDir, "audio_only.mp4")
        AudioInputFixtures.createAacM4a(
            input,
            durationSeconds = 1,
            sampleRate = SAMPLE_RATE_44K,
            channels = STEREO,
            bitrate = 96_000,
        )
        val output = File(tempDir, "audio_only_out.mp4")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Audio-only MP4 must be rejected")
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err is VideoCompressionError.UnsupportedSourceFormat,
            "Expected UnsupportedSourceFormat, got ${err::class.simpleName}: ${err.message}",
        )
        assertTrue(!output.exists(), "No partial output should exist")
    }

    @Test
    fun videoOnlyNoAudioTrack_compressesSuccessfully() = runTest {
        val input = File(tempDir, "video_only.mp4")
        Mp4Generator.generateMp4(input, frameCount = VIDEO_FRAME_COUNT)
        val output = File(tempDir, "video_only_out.mp4")

        val result = compressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isSuccess, "Video-only input must compress: ${result.exceptionOrNull()}")
        assertTrue(output.exists() && output.length() > 0)
        assertTrue(hasVideoTrack(output), "Output must contain a video track")
        assertTrue(!hasAudioTrack(output), "Output must NOT contain an audio track")
    }

    private companion object {
        const val VIDEO_FRAME_COUNT = 30
    }
}
