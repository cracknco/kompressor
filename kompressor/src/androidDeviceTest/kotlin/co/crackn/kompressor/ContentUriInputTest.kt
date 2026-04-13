package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.TestContentProvider
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.video.AndroidVideoCompressor
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coverage of `content://` URI inputs — the URI form Android apps receive from
 * `ACTION_OPEN_DOCUMENT`, share sheets, etc. Without this test, the dedicated `content://`
 * branches in `probeInputFormat` (uses `Context/Uri` overload of `setDataSource`),
 * `resolveMediaInputSize` (uses `ContentResolver.openFileDescriptor`), and `toMediaItemUri`
 * (passes through unchanged) all ran only in production, never in CI.
 */
class ContentUriInputTest {

    private lateinit var tempDir: File
    private val audioCompressor = AndroidAudioCompressor()
    private val videoCompressor = AndroidVideoCompressor()
    private val imageCompressor = AndroidImageCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-content-uri-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun audio_compressFromContentUri_succeeds() = runTest {
        val input = File(tempDir, "input.wav")
        input.writeBytes(WavGenerator.generateWavBytes(2, SAMPLE_RATE_44K, STEREO))
        val output = File(tempDir, "output.m4a")
        // URI form apps get back from OPEN_DOCUMENT / share sheets: content://<authority>/<path>.
        val contentUri = TestContentProvider.contentUriFor("${tempDir.name}/${input.name}")

        val result = audioCompressor.compress(
            inputPath = contentUri.toString(),
            outputPath = output.absolutePath,
            config = AudioCompressionConfig(),
        )

        assertTrue(
            result.isSuccess,
            "Audio compress from content:// URI must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0, "Output file must exist and be non-empty")
    }

    @Test
    fun image_compressFromContentUri_succeeds() = runTest {
        // Third compressor covered end-to-end for content:// inputs. Exercises the
        // `ContentUriSource` path in `AndroidImageCompressor` — four distinct operations
        // (fd stat, ExifInterface(stream), decodeStream bounds, decodeStream bitmap) all
        // going through `ContentResolver` instead of `java.io.File`.
        val input = createTestImage(tempDir, 800, 600)
        val output = File(tempDir, "image_out.jpg")
        val contentUri = TestContentProvider.contentUriFor("${tempDir.name}/${input.name}")

        val result = imageCompressor.compress(
            inputPath = contentUri.toString(),
            outputPath = output.absolutePath,
        )

        assertTrue(
            result.isSuccess,
            "Image compress from content:// URI must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0, "Output file must exist and be non-empty")
        // Verify dimensions survived end-to-end through the stream-based decode.
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(output.absolutePath, options)
        kotlin.test.assertEquals(800, options.outWidth)
        kotlin.test.assertEquals(600, options.outHeight)
    }

    @Test
    fun video_compressFromContentUri_succeeds() = runTest {
        val input = File(tempDir, "input.mp4")
        AudioInputFixtures.createMp4WithVideoAndAudio(input, durationSeconds = 1)
        val output = File(tempDir, "output.mp4")
        val contentUri = TestContentProvider.contentUriFor("${tempDir.name}/${input.name}")

        val result = videoCompressor.compress(
            inputPath = contentUri.toString(),
            outputPath = output.absolutePath,
        )

        assertTrue(
            result.isSuccess,
            "Video compress from content:// URI must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0, "Output file must exist and be non-empty")
    }
}
