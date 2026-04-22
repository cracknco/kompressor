/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.logging.KompressorLogger
import co.crackn.kompressor.logging.LogLevel
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.testutil.AudioInputFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coverage for the CRA-95 Stream + Bytes dispatch on Android — the sibling of
 * `iosTest/StreamAndBytesEndToEndTest`. Exercises:
 *
 *  - `MediaSource.Local.Stream` input routes through
 *    `resolveStreamOrBytesToTempFile` → `materializeToTempFile` → legacy compressor body,
 *    producing bitwise-identical output to the legacy FilePath overload.
 *  - `MediaSource.Local.Bytes` input materializes through the same Bytes path; the image
 *    compressor short-circuits via `decodeByteArray` instead.
 *  - The `BYTES_WARN_THRESHOLD` OOM warn fires for audio / video bytes inputs > 10 MB and is
 *    suppressed for the image compressor.
 *  - `MediaDestination.Local.Stream` output streams the compressed temp file into a consumer
 *    `okio.Sink`, byte-identical to the legacy FilePath write, and honours `closeOnFinish`.
 *
 * Device-test-only because the Media3 `Transformer` and Bitmap decoder require a real
 * emulator or physical device.
 */
class StreamAndBytesEndToEndTest {

    private lateinit var tempDir: File
    private val audioCompressor = AndroidAudioCompressor()
    private val videoCompressor = AndroidVideoCompressor()
    private val imageCompressor = AndroidImageCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "kompressor-stream-bytes-e2e").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- Stream input ---------------------------------------------------------

    @Test
    fun image_streamInput_bitwiseIdenticalToFilePath() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyOut = File(tempDir, "legacy.jpg")
        val novelOut = File(tempDir, "novel.jpg")

        val legacy = imageCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            ImageCompressionConfig(),
        )
        val novel = imageCompressor.compress(
            input = MediaSource.Local.Stream(input.inputStream().source(), sizeHint = input.length()),
            output = MediaDestination.Local.FilePath(novelOut.absolutePath),
            config = ImageCompressionConfig(),
        )

        withClue("legacy: ${legacy.exceptionOrNull()}") { legacy.isSuccess shouldBe true }
        withClue("novel: ${novel.exceptionOrNull()}") { novel.isSuccess shouldBe true }
        withClue("Stream input must produce byte-identical output to legacy FilePath") {
            novelOut.readBytes().contentEquals(legacyOut.readBytes()) shouldBe true
        }
    }

    @Test
    fun audio_streamInput_bitwiseIdenticalToFilePath() = runTest {
        val input = File(tempDir, "in.wav").apply {
            writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
        }
        val legacyOut = File(tempDir, "legacy.m4a")
        val novelOut = File(tempDir, "novel.m4a")

        val legacy = audioCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            AudioCompressionConfig(),
        )
        val novel = audioCompressor.compress(
            input = MediaSource.Local.Stream(input.inputStream().source(), sizeHint = input.length()),
            output = MediaDestination.Local.FilePath(novelOut.absolutePath),
            config = AudioCompressionConfig(),
        )

        withClue("legacy: ${legacy.exceptionOrNull()}") { legacy.isSuccess shouldBe true }
        withClue("novel: ${novel.exceptionOrNull()}") { novel.isSuccess shouldBe true }
        novelOut.readBytes().contentEquals(legacyOut.readBytes()) shouldBe true
    }

    @Test
    fun video_streamInput_bitwiseIdenticalToFilePath() = runTest {
        val input = File(tempDir, "in.mp4").also {
            AudioInputFixtures.createMp4WithVideoAndAudio(it, durationSeconds = 1)
        }
        val legacyOut = File(tempDir, "legacy.mp4")
        val novelOut = File(tempDir, "novel.mp4")

        val legacy = videoCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            VideoCompressionConfig(),
        )
        val novel = videoCompressor.compress(
            input = MediaSource.Local.Stream(input.inputStream().source(), sizeHint = input.length()),
            output = MediaDestination.Local.FilePath(novelOut.absolutePath),
            config = VideoCompressionConfig(),
        )

        withClue("legacy: ${legacy.exceptionOrNull()}") { legacy.isSuccess shouldBe true }
        withClue("novel: ${novel.exceptionOrNull()}") { novel.isSuccess shouldBe true }
        novelOut.readBytes().contentEquals(legacyOut.readBytes()) shouldBe true
    }

    @Test
    fun stream_closeOnFinishTrue_closesSource() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingFileSource(input)
        val output = File(tempDir, "out.jpg")

        val result = imageCompressor.compress(
            input = MediaSource.Local.Stream(tracking, sizeHint = input.length(), closeOnFinish = true),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("Stream.closeOnFinish=true must close the source at end of compress") {
            tracking.closed shouldBe true
        }
    }

    @Test
    fun stream_closeOnFinishFalse_leavesSourceOpen() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingFileSource(input)
        val output = File(tempDir, "out.jpg")

        val result = imageCompressor.compress(
            input = MediaSource.Local.Stream(tracking, sizeHint = input.length(), closeOnFinish = false),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("Stream.closeOnFinish=false must NOT close the caller-owned source") {
            tracking.closed shouldBe false
        }
    }

    // --- Bytes input ----------------------------------------------------------

    @Test
    fun image_bytesInput_bitwiseIdenticalToFilePath() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val bytes = input.readBytes()
        val legacyOut = File(tempDir, "legacy.jpg")
        val novelOut = File(tempDir, "novel.jpg")

        val legacy = imageCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            ImageCompressionConfig(),
        )
        val novel = imageCompressor.compress(
            input = MediaSource.Local.Bytes(bytes),
            output = MediaDestination.Local.FilePath(novelOut.absolutePath),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        withClue("Bytes input must produce byte-identical output to legacy FilePath") {
            novelOut.readBytes().contentEquals(legacyOut.readBytes()) shouldBe true
        }
    }

    @Test
    fun audio_bytesInput_bitwiseIdenticalToFilePath() = runTest {
        val input = File(tempDir, "in.wav").apply {
            writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
        }
        val bytes = input.readBytes()
        val legacyOut = File(tempDir, "legacy.m4a")
        val novelOut = File(tempDir, "novel.m4a")

        val legacy = audioCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            AudioCompressionConfig(),
        )
        val novel = audioCompressor.compress(
            input = MediaSource.Local.Bytes(bytes),
            output = MediaDestination.Local.FilePath(novelOut.absolutePath),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        novelOut.readBytes().contentEquals(legacyOut.readBytes()) shouldBe true
    }

    @Test
    fun audio_bytesAboveThreshold_emitsOomWarn() = runTest {
        // Build a synthetic WAV just over BYTES_WARN_THRESHOLD (10 MB). WAV is safe for the
        // audio pipeline to extract, so the compress() call itself still succeeds — the purpose
        // is to observe the WARN, not to validate the transcode.
        val bytes = synthesizeWavBytes(sizeBytes = BYTES_WARN_THRESHOLD + 1, sampleRate = SAMPLE_RATE_44K)
        val recording = RecordingLogger()
        val compressor = AndroidAudioCompressor(logger = SafeLogger(recording))
        val output = File(tempDir, "out.m4a")

        val result = compressor.compress(
            input = MediaSource.Local.Bytes(bytes),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = AudioCompressionConfig(),
        )

        withClue("compress failed: ${result.exceptionOrNull()}") { result.isSuccess shouldBe true }
        val warns = recording.records.filter {
            it.level == LogLevel.WARN && it.message.contains("MediaSource.Local.Bytes")
        }
        withClue("Expected WARN for >10MB Bytes audio input, got records=${recording.records}") {
            warns.isNotEmpty() shouldBe true
        }
    }

    @Test
    fun image_bytesAboveThreshold_doesNotEmitOomWarn() = runTest {
        // Images short-circuit Bytes → BitmapFactory.decodeByteArray (no temp-file materialization),
        // and the WARN is suppressed for MediaType.IMAGE. Use a JPEG payload padded past threshold
        // to confirm the suppression.
        val realImage = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE).readBytes()
        val padded = ByteArray(BYTES_WARN_THRESHOLD + 1).also { realImage.copyInto(it) }
        val recording = RecordingLogger()
        val compressor = AndroidImageCompressor(logger = SafeLogger(recording))
        val output = File(tempDir, "out.jpg")

        // Compress may fail on the padded tail; we only care that no OOM warn fires.
        compressor.compress(
            input = MediaSource.Local.Bytes(padded),
            output = MediaDestination.Local.FilePath(output.absolutePath),
            config = ImageCompressionConfig(),
        )

        val warns = recording.records.filter {
            it.level == LogLevel.WARN && it.message.contains("MediaSource.Local.Bytes")
        }
        withClue("Image Bytes must not emit OOM WARN, got: $warns") { warns.isEmpty() shouldBe true }
    }

    // --- Stream output --------------------------------------------------------

    @Test
    fun image_streamOutput_bitwiseIdenticalToFilePath() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyOut = File(tempDir, "legacy.jpg")
        val consumer = Buffer()

        val legacy = imageCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            ImageCompressionConfig(),
        )
        val novel = imageCompressor.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.Stream(consumer),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        withClue("stream output: ${novel.exceptionOrNull()}") { novel.isSuccess shouldBe true }
        consumer.readByteArray().contentEquals(legacyOut.readBytes()) shouldBe true
    }

    @Test
    fun audio_streamOutput_bitwiseIdenticalToFilePath() = runTest {
        val input = File(tempDir, "in.wav").apply {
            writeBytes(WavGenerator.generateWavBytes(1, SAMPLE_RATE_44K, STEREO))
        }
        val legacyOut = File(tempDir, "legacy.m4a")
        val consumer = Buffer()

        val legacy = audioCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            AudioCompressionConfig(),
        )
        val novel = audioCompressor.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.Stream(consumer),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        withClue("stream output: ${novel.exceptionOrNull()}") { novel.isSuccess shouldBe true }
        consumer.readByteArray().contentEquals(legacyOut.readBytes()) shouldBe true
    }

    @Test
    fun video_streamOutput_bitwiseIdenticalToFilePath() = runTest {
        val input = File(tempDir, "in.mp4").also {
            Mp4Generator.generateMp4(output = it, frameCount = VIDEO_FRAME_COUNT, fps = VIDEO_FPS)
        }
        val legacyOut = File(tempDir, "legacy.mp4")
        val consumer = Buffer()

        val legacy = videoCompressor.compress(
            input.absolutePath,
            legacyOut.absolutePath,
            VideoCompressionConfig(),
        )
        val novel = videoCompressor.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.Stream(consumer),
            config = VideoCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        withClue("stream output: ${novel.exceptionOrNull()}") { novel.isSuccess shouldBe true }
        consumer.readByteArray().contentEquals(legacyOut.readBytes()) shouldBe true
    }

    @Test
    fun streamOutput_closeOnFinishTrue_closesSink() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingSink()

        val result = imageCompressor.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.Stream(tracking, closeOnFinish = true),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("Stream destination with closeOnFinish=true must close the sink") {
            tracking.closed shouldBe true
        }
    }

    @Test
    fun streamOutput_closeOnFinishFalse_leavesSinkOpen() = runTest {
        val input = createTestImage(tempDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingSink()

        val result = imageCompressor.compress(
            input = MediaSource.Local.FilePath(input.absolutePath),
            output = MediaDestination.Local.Stream(tracking, closeOnFinish = false),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("Stream destination with closeOnFinish=false must leave the sink open") {
            tracking.closed shouldBe false
        }
    }

    // --- helpers --------------------------------------------------------------

    /** Minimal WAV synthesizer — silence padded to [sizeBytes] so we hit the WARN threshold. */
    private fun synthesizeWavBytes(sizeBytes: Int, sampleRate: Int): ByteArray {
        val header = WavGenerator.generateWavBytes(1, sampleRate, STEREO).copyOfRange(0, WAV_HEADER_SIZE)
        // Rewrite the data-chunk size so the WAV parser believes the payload goes all the way
        // to the end of the buffer; otherwise MediaExtractor stops at the original duration.
        val dataSize = sizeBytes - WAV_HEADER_SIZE
        val out = ByteArray(sizeBytes)
        header.copyInto(out)
        // RIFF chunk size at offset 4 (file size - 8) and data-chunk size at offset 40.
        writeLeInt(out, RIFF_SIZE_OFFSET, sizeBytes - 8)
        writeLeInt(out, DATA_SIZE_OFFSET, dataSize)
        return out
    }

    private fun writeLeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private data class LogRecord(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private class RecordingLogger : KompressorLogger {
        val records: MutableList<LogRecord> = mutableListOf()
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            records += LogRecord(level, tag, message, throwable)
        }
    }

    /** okio [okio.Source] wrapping a [File] that reports a single `close()` observation. */
    private class TrackingFileSource(file: File) : okio.Source {
        private val delegate = file.inputStream().source()
        var closed: Boolean = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long = delegate.read(sink, byteCount)
        override fun timeout(): okio.Timeout = delegate.timeout()
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class TrackingSink : okio.Sink {
        private val discard = Buffer()
        var closed: Boolean = false
            private set

        override fun write(source: Buffer, byteCount: Long) {
            source.read(discard, byteCount)
            discard.clear()
        }

        override fun flush() = Unit
        override fun timeout(): okio.Timeout = okio.Timeout.NONE
        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val IMAGE_SIDE = 480
        const val VIDEO_FRAME_COUNT = 30
        const val VIDEO_FPS = 30
        const val WAV_HEADER_SIZE = 44
        const val RIFF_SIZE_OFFSET = 4
        const val DATA_SIZE_OFFSET = 40
    }
}
