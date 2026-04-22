/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.logging.KompressorLogger
import co.crackn.kompressor.logging.LogLevel
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.createTestImage
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Timeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * iOS end-to-end coverage for CRA-95 Stream + Bytes dispatch — sibling of the Android
 * `androidDeviceTest/StreamAndBytesEndToEndTest`. Validates:
 *
 *  - `MediaSource.Local.Stream` input routes through
 *    `resolveStreamOrBytesToTempFile` → `materializeToTempFile` → legacy compressor body.
 *  - `MediaSource.Local.Bytes` input materializes the same way for audio / video; the image
 *    compressor short-circuits via NSData → `UIImage(data:)` (no temp-file hop).
 *  - `BYTES_WARN_THRESHOLD` OOM WARN fires for audio bytes > 10 MB and is suppressed for
 *    the image compressor (parity with Android).
 *  - `MediaDestination.Local.Stream` output streams the compressed temp file into a consumer
 *    `okio.Sink` and honours `closeOnFinish`.
 *
 * **Equivalence model.** On iOS, JPEG re-encoding is deterministic so image outputs stay
 * bitwise-identical across two different source paths. For audio (M4A) and video (MP4) the
 * AVFoundation export writes path-dependent container metadata (`mvhd` / `mdhd`
 * creation-time & modification-time timestamps), so the novel output can never be exactly
 * byte-equal to the legacy output. The correct invariant is *compression-outcome
 * equivalence*: both paths must succeed and the output sizes must agree within
 * [AV_SIZE_TOLERANCE_BYTES] (small enough to catch real regressions, loose enough to
 * tolerate container-metadata drift). The Android sibling keeps bitwise comparison because
 * Media3 Transformer output is deterministic.
 */
class StreamAndBytesEndToEndTest {

    private lateinit var testDir: String
    private val image = IosImageCompressor()
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-stream-bytes-ios-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    // --- Stream input ---------------------------------------------------------

    @Test
    fun image_streamInput_bitwiseIdenticalToFilePath() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val inputBytes = readBytes(inputPath)
        val legacyPath = testDir + "legacy.jpg"
        val novelPath = testDir + "novel.jpg"

        val legacy = image.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            ImageCompressionConfig(),
        )
        val novel = image.compress(
            input = MediaSource.Local.Stream(
                Buffer().apply { write(inputBytes) },
                sizeHint = inputBytes.size.toLong(),
            ),
            output = MediaDestination.Local.FilePath(novelPath),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun audio_streamInput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = createTestWav()
        val inputBytes = readBytes(inputPath)
        val legacyPath = testDir + "legacy.m4a"
        val novelPath = testDir + "novel.m4a"

        val legacy = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            AudioCompressionConfig(),
        )
        val novel = audio.compress(
            input = MediaSource.Local.Stream(
                Buffer().apply { write(inputBytes) },
                sizeHint = inputBytes.size.toLong(),
            ),
            output = MediaDestination.Local.FilePath(novelPath),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertSizeMatchesWithinTolerance(novelPath, legacyPath)
    }

    @Test
    fun video_streamInput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "in.mp4", frameCount = VIDEO_FRAME_COUNT)
        val inputBytes = readBytes(inputPath)
        val legacyPath = testDir + "legacy.mp4"
        val novelPath = testDir + "novel.mp4"

        val legacy = video.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            VideoCompressionConfig(),
        )
        val novel = video.compress(
            input = MediaSource.Local.Stream(
                Buffer().apply { write(inputBytes) },
                sizeHint = inputBytes.size.toLong(),
            ),
            output = MediaDestination.Local.FilePath(novelPath),
            config = VideoCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertSizeMatchesWithinTolerance(novelPath, legacyPath)
    }

    @Test
    fun stream_closeOnFinishTrue_closesSource() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingSource(readBytes(inputPath))
        val output = testDir + "out.jpg"

        val result = image.compress(
            input = MediaSource.Local.Stream(tracking, sizeHint = tracking.totalBytes, closeOnFinish = true),
            output = MediaDestination.Local.FilePath(output),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("closeOnFinish=true must close the caller-supplied Source") { tracking.closed shouldBe true }
    }

    @Test
    fun stream_closeOnFinishFalse_leavesSourceOpen() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingSource(readBytes(inputPath))
        val output = testDir + "out.jpg"

        val result = image.compress(
            input = MediaSource.Local.Stream(tracking, sizeHint = tracking.totalBytes, closeOnFinish = false),
            output = MediaDestination.Local.FilePath(output),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("closeOnFinish=false must leave the caller-owned Source open") { tracking.closed shouldBe false }
    }

    // --- Bytes input ----------------------------------------------------------

    @Test
    fun image_bytesInput_bitwiseIdenticalToFilePath() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val inputBytes = readBytes(inputPath)
        val legacyPath = testDir + "legacy.jpg"
        val novelPath = testDir + "novel.jpg"

        val legacy = image.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            ImageCompressionConfig(),
        )
        val novel = image.compress(
            input = MediaSource.Local.Bytes(inputBytes),
            output = MediaDestination.Local.FilePath(novelPath),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        readBytes(novelPath).contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun audio_bytesInput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = createTestWav()
        val inputBytes = readBytes(inputPath)
        val legacyPath = testDir + "legacy.m4a"
        val novelPath = testDir + "novel.m4a"

        val legacy = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            AudioCompressionConfig(),
        )
        val novel = audio.compress(
            input = MediaSource.Local.Bytes(inputBytes),
            output = MediaDestination.Local.FilePath(novelPath),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertSizeMatchesWithinTolerance(novelPath, legacyPath)
    }

    @Test
    fun audio_bytesAboveThreshold_emitsOomWarn() = runTest {
        // Synthetic WAV just over the 10 MB threshold. The WARN is emitted in
        // `resolveStreamOrBytesToTempFile` *before* AVFoundation touches the file, so the
        // assertion intentionally ignores whether the downstream encode succeeds — the
        // contract under test is "large Bytes input triggers an OOM WARN". Whether the
        // fabricated WAV is actually decodable by AVAssetReader is irrelevant here.
        val bytes = synthesizeWavBytes(sizeBytes = BYTES_WARN_THRESHOLD + 1, sampleRate = WAV_SAMPLE_RATE)
        val recording = RecordingLogger()
        val compressor = IosAudioCompressor(logger = SafeLogger(recording))
        val output = testDir + "out.m4a"

        compressor.compress(
            input = MediaSource.Local.Bytes(bytes),
            output = MediaDestination.Local.FilePath(output),
            config = AudioCompressionConfig(),
        )

        val warns = recording.records.filter {
            it.level == LogLevel.WARN && it.message.contains("MediaSource.Local.Bytes")
        }
        withClue("Expected WARN for >10MB Bytes audio input, got records=${recording.records}") {
            warns.isNotEmpty() shouldBe true
        }
    }

    @Test
    fun image_bytesAboveThreshold_doesNotEmitOomWarn() = runTest {
        // Images never hit the resolver: the iOS image compressor short-circuits Bytes → NSData →
        // UIImage(data:) before dispatch. Even if it did, MediaType.IMAGE suppresses the WARN.
        val realImage = readBytes(createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE))
        val padded = ByteArray(BYTES_WARN_THRESHOLD + 1).also { realImage.copyInto(it) }
        val recording = RecordingLogger()
        val compressor = IosImageCompressor(logger = SafeLogger(recording))

        compressor.compress(
            input = MediaSource.Local.Bytes(padded),
            output = MediaDestination.Local.FilePath(testDir + "out.jpg"),
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
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val legacyPath = testDir + "legacy.jpg"
        val consumer = Buffer()

        val legacy = image.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            ImageCompressionConfig(),
        )
        val novel = image.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.Stream(consumer),
            config = ImageCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        consumer.readByteArray().contentEquals(readBytes(legacyPath)) shouldBe true
    }

    @Test
    fun audio_streamOutput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = createTestWav()
        val legacyPath = testDir + "legacy.m4a"
        val consumer = Buffer()

        val legacy = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            AudioCompressionConfig(),
        )
        val novel = audio.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.Stream(consumer),
            config = AudioCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertSizeMatchesWithinTolerance(consumer.size, readBytes(legacyPath).size.toLong())
    }

    @Test
    fun video_streamOutput_matchesFilePathCompressionOutcome() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "in.mp4", frameCount = VIDEO_FRAME_COUNT)
        val legacyPath = testDir + "legacy.mp4"
        val consumer = Buffer()

        val legacy = video.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            VideoCompressionConfig(),
        )
        val novel = video.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.Stream(consumer),
            config = VideoCompressionConfig(),
        )

        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true
        assertSizeMatchesWithinTolerance(consumer.size, readBytes(legacyPath).size.toLong())
    }

    @Test
    fun streamOutput_closeOnFinishTrue_closesSink() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingSink()

        val result = image.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.Stream(tracking, closeOnFinish = true),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("closeOnFinish=true must close the caller-supplied Sink") { tracking.closed shouldBe true }
    }

    @Test
    fun streamOutput_closeOnFinishFalse_leavesSinkOpen() = runTest {
        val inputPath = createTestImage(testDir, IMAGE_SIDE, IMAGE_SIDE)
        val tracking = TrackingSink()

        val result = image.compress(
            input = MediaSource.Local.FilePath(inputPath),
            output = MediaDestination.Local.Stream(tracking, closeOnFinish = false),
            config = ImageCompressionConfig(),
        )

        result.isSuccess shouldBe true
        withClue("closeOnFinish=false must leave the caller-owned Sink open") { tracking.closed shouldBe false }
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Assert two audio/video outputs have sizes within [AV_SIZE_TOLERANCE_BYTES]. Used in
     * lieu of bitwise comparison because AVFoundation's `mvhd` / `mdhd` boxes carry
     * source-path-dependent creation/modification timestamps.
     */
    private fun assertSizeMatchesWithinTolerance(novelPath: String, legacyPath: String) {
        assertSizeMatchesWithinTolerance(
            novelSize = readBytes(novelPath).size.toLong(),
            legacySize = readBytes(legacyPath).size.toLong(),
        )
    }

    private fun assertSizeMatchesWithinTolerance(novelSize: Long, legacySize: Long) {
        val delta = abs(novelSize - legacySize)
        withClue(
            "Expected novel/legacy output sizes within $AV_SIZE_TOLERANCE_BYTES bytes — " +
                "novel=$novelSize legacy=$legacySize delta=$delta",
        ) {
            (novelSize > 0 && legacySize > 0 && delta <= AV_SIZE_TOLERANCE_BYTES) shouldBe true
        }
    }

    private fun createTestWav(): String {
        val bytes = WavGenerator.generateWavBytes(AUDIO_DURATION_S, WAV_SAMPLE_RATE, WAV_CHANNELS)
        val path = testDir + "in.wav"
        writeBytes(path, bytes)
        return path
    }

    /**
     * Synthesize a WAV header + [sizeBytes] of PCM silence so tests can hit
     * [BYTES_WARN_THRESHOLD] without shipping a 10 MB asset. Rewrites the RIFF / data chunk
     * sizes so AVAsset readers accept the padded buffer as a well-formed WAV.
     */
    private fun synthesizeWavBytes(sizeBytes: Int, sampleRate: Int): ByteArray {
        val header = WavGenerator.generateWavBytes(1, sampleRate, WAV_CHANNELS).copyOfRange(0, WAV_HEADER_SIZE)
        val dataSize = sizeBytes - WAV_HEADER_SIZE
        val out = ByteArray(sizeBytes)
        header.copyInto(out)
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

    /** okio [okio.Source] backed by a [ByteArray] that records whether [close] was invoked. */
    private class TrackingSource(payload: ByteArray) : okio.Source {
        private val buffer = Buffer().apply { write(payload) }
        val totalBytes: Long = payload.size.toLong()
        var closed: Boolean = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long = buffer.read(sink, byteCount)
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {
            closed = true
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
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val IMAGE_SIDE = 512
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
        const val WAV_HEADER_SIZE = 44
        const val RIFF_SIZE_OFFSET = 4
        const val DATA_SIZE_OFFSET = 40

        /**
         * Max byte delta between the legacy FilePath path and the novel Stream/Bytes path for
         * audio / video outputs on iOS. Accommodates `mvhd` / `mdhd` container-metadata drift
         * (path-dependent timestamps written by AVFoundation) without masking real regressions
         * — the produced MP4 / M4A payloads differ only at the tens-of-bytes level.
         */
        const val AV_SIZE_TOLERANCE_BYTES: Long = 1024
    }
}
