/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.TestContentProvider
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.video.AndroidVideoCompressor
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coverage of the CRA-93 builder overloads — exercises
 * `compress(MediaSource, MediaDestination, ...)` with `MediaSource.of(Uri)` /
 * `MediaSource.of(ParcelFileDescriptor)` on all three Android compressors.
 *
 * The legacy string-path overload is covered by [ContentUriInputTest]; this test validates the
 * new builder dispatch (commonMain interface → `AndroidMediaDispatch.toAndroidInputPath` →
 * legacy compressor body), including the PFD-materialization / close-on-finish contract and
 * HTTP scheme rejection that happens inside the builder before the compressor runs.
 *
 * Runs on androidDeviceTest (not PR CI) — the real Media3 `Transformer` / Bitmap decoder
 * plumbing needs a device or emulator.
 */
class UriInputEndToEndTest {

    private lateinit var tempDir: File
    private val audioCompressor = AndroidAudioCompressor()
    private val videoCompressor = AndroidVideoCompressor()
    private val imageCompressor = AndroidImageCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-uri-input-e2e-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun audio_viaUriBuilder_compressesSuccessfully() = runTest {
        val input = File(tempDir, "in.wav")
        input.writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
        val output = File(tempDir, "out.m4a")
        val contentUri = TestContentProvider.contentUriFor("${tempDir.name}/${input.name}")

        val result = audioCompressor.compress(
            input = MediaSource.of(contentUri),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(),
        )

        assertTrue(
            result.isSuccess,
            "Audio compress via MediaSource.of(Uri) must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
    }

    @Test
    fun image_viaUriBuilder_compressesSuccessfully() = runTest {
        val input = createTestImage(tempDir, 640, 480)
        val output = File(tempDir, "out.jpg")
        val contentUri = TestContentProvider.contentUriFor("${tempDir.name}/${input.name}")

        val result = imageCompressor.compress(
            input = MediaSource.of(contentUri),
            output = MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(
            result.isSuccess,
            "Image compress via MediaSource.of(Uri) must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
    }

    @Test
    fun video_viaUriBuilder_compressesSuccessfully() = runTest {
        val input = File(tempDir, "in.mp4")
        AudioInputFixtures.createMp4WithVideoAndAudio(input, durationSeconds = 1)
        val output = File(tempDir, "out.mp4")
        val contentUri = TestContentProvider.contentUriFor("${tempDir.name}/${input.name}")

        val result = videoCompressor.compress(
            input = MediaSource.of(contentUri),
            output = MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(
            result.isSuccess,
            "Video compress via MediaSource.of(Uri) must succeed: ${result.exceptionOrNull()}",
        )
        assertTrue(output.exists() && output.length() > 0)
    }

    @Test
    fun image_viaPfdBuilder_compressesAndClosesPfd() = runTest {
        // The PFD builder's contract (AndroidMediaSources.kt KDoc) is that Kompressor materializes
        // the FD's contents to a temp file and closes the PFD at the end of compression — success
        // or failure. This test verifies the success-path close: after compress() returns, the PFD
        // `fileDescriptor.valid()` flips to false.
        val input = createTestImage(tempDir, 320, 240)
        val output = File(tempDir, "out.jpg")
        val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)

        val result = imageCompressor.compress(
            input = MediaSource.of(pfd),
            output = MediaDestination.Local.FilePath(output.absolutePath),
        )

        assertTrue(
            result.isSuccess,
            "Image compress via MediaSource.of(pfd) must succeed: ${result.exceptionOrNull()}",
        )
        assertFalse(
            pfd.fileDescriptor.valid(),
            "PFD must be closed by Kompressor at end of compression (closeOnFinish contract)",
        )
    }

    @Test
    fun uriBuilder_rejectsHttpScheme_withCanonicalMessage() {
        // The rejection lives in the builder — it fires before any compressor runs, so this
        // test doesn't even need to spin up `AndroidImageCompressor`. The exact string is a
        // cross-platform invariant shared with the iOS T5 sibling.
        val httpUri = Uri.parse("https://example.com/video.mp4")

        val e = assertFailsWith<IllegalArgumentException> { MediaSource.of(httpUri) }
        assertTrue(
            e.message == "Remote URLs not supported. Download the content locally first.",
            "Unexpected message: ${e.message}",
        )
    }

    @Test
    fun uriOutputBuilder_rejectsHttpScheme_withCanonicalMessage() {
        // Sibling check on the destination side — the two invariant strings differ only in the
        // "Download the content locally first." / "Write locally first then upload." split.
        val httpUri = Uri.parse("http://example.com/upload")

        val e = assertFailsWith<IllegalArgumentException> { MediaDestination.of(httpUri) }
        assertTrue(
            e.message == "Remote URLs not supported. Write locally first then upload.",
            "Unexpected message: ${e.message}",
        )
    }
}

