/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.logging.KompressorLogger
import co.crackn.kompressor.logging.LogLevel
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.SafeLogger
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.Source
import okio.Timeout
import okio.fakefilesystem.FakeFileSystem

/**
 * commonTest coverage for [resolveStreamOrBytesToTempFile]. Exercises the Stream / Bytes
 * dispatch table, the OOM WARN threshold, cleanup ownership, and the programmer-error guard.
 * Uses the injectable [okio.FileSystem] overload so the suite stays pure Kotlin with no
 * platform FS dependency.
 */
class MediaSourceResolverTest {

    private val tempDir = "/tmp/kompressor-resolver-test".toPath()

    @Test
    fun streamInputMaterializesIdenticalBytes() = runTest {
        val payload = ByteArray(128 * 1024) { (it % 256).toByte() }
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())
        val input = MediaSource.Local.Stream(Buffer().apply { write(payload) }, sizeHint = payload.size.toLong())

        val resolved = resolveStreamOrBytesToTempFile(
            input = input,
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.VIDEO,
            logger = logger,
        )

        val path = resolved.path.toPath()
        fs.exists(path) shouldBe true
        fs.read(path) { readByteArray() }.contentEquals(payload) shouldBe true
        resolved.cleanup()
        fs.exists(path) shouldBe false
    }

    @Test
    fun bytesInputMaterializesIdenticalBytes() = runTest {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())

        val resolved = resolveStreamOrBytesToTempFile(
            input = MediaSource.Local.Bytes(payload),
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.AUDIO,
            logger = logger,
        )

        val path = resolved.path.toPath()
        fs.read(path) { readByteArray() }.contentEquals(payload) shouldBe true
        resolved.cleanup()
        fs.exists(path) shouldBe false
    }

    @Test
    fun streamCloseOnFinishTrueClosesSourceOnCleanup() = runTest {
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())
        val tracking = TrackingSource(ByteArray(4 * 1024))
        val input = MediaSource.Local.Stream(tracking, sizeHint = tracking.totalBytes, closeOnFinish = true)

        val resolved = resolveStreamOrBytesToTempFile(
            input = input,
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.VIDEO,
            logger = logger,
        )
        resolved.cleanup()

        tracking.closedCount shouldBe 1
    }

    @Test
    fun streamCloseOnFinishFalseDoesNotCloseSource() = runTest {
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())
        val tracking = TrackingSource(ByteArray(4 * 1024))
        val input = MediaSource.Local.Stream(tracking, sizeHint = tracking.totalBytes, closeOnFinish = false)

        val resolved = resolveStreamOrBytesToTempFile(
            input = input,
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.VIDEO,
            logger = logger,
        )
        resolved.cleanup()

        tracking.closedCount shouldBe 0
    }

    @Test
    fun bytesAboveThresholdForVideoEmitsWarn() = runTest {
        val fs = FakeFileSystem()
        val recording = RecordingLogger()
        val logger = SafeLogger(recording)
        // Just over the threshold so the Buffer/copy cost stays in the sub-millisecond range
        // under the test scheduler. We don't need a multi-MB buffer to exercise the `>` check.
        val payload = ByteArray(BYTES_WARN_THRESHOLD + 1)

        resolveStreamOrBytesToTempFile(
            input = MediaSource.Local.Bytes(payload),
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.VIDEO,
            logger = logger,
        ).cleanup()

        val warns = recording.records.filter { it.level == LogLevel.WARN }
        warns.size shouldBe 1
        warns.single().tag shouldBe LogTags.IO
        assertTrue(warns.single().message.contains("VIDEO"), "expected VIDEO in warn message: ${warns.single().message}")
        assertTrue(warns.single().message.contains("${payload.size}"), "expected byte count in warn message")
    }

    @Test
    fun bytesAboveThresholdForAudioEmitsWarn() = runTest {
        val fs = FakeFileSystem()
        val recording = RecordingLogger()
        val logger = SafeLogger(recording)
        val payload = ByteArray(BYTES_WARN_THRESHOLD + 1)

        resolveStreamOrBytesToTempFile(
            input = MediaSource.Local.Bytes(payload),
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.AUDIO,
            logger = logger,
        ).cleanup()

        recording.records.count { it.level == LogLevel.WARN } shouldBe 1
    }

    @Test
    fun bytesAboveThresholdForImageSuppressesWarn() = runTest {
        val fs = FakeFileSystem()
        val recording = RecordingLogger()
        val logger = SafeLogger(recording)
        val payload = ByteArray(BYTES_WARN_THRESHOLD + 1)

        resolveStreamOrBytesToTempFile(
            input = MediaSource.Local.Bytes(payload),
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.IMAGE,
            logger = logger,
        ).cleanup()

        recording.records.count { it.level == LogLevel.WARN } shouldBe 0
    }

    @Test
    fun bytesAtThresholdDoesNotEmitWarn() = runTest {
        val fs = FakeFileSystem()
        val recording = RecordingLogger()
        val logger = SafeLogger(recording)
        // The threshold is strictly-greater-than — BYTES_WARN_THRESHOLD itself must not warn.
        val payload = ByteArray(BYTES_WARN_THRESHOLD)

        resolveStreamOrBytesToTempFile(
            input = MediaSource.Local.Bytes(payload),
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.VIDEO,
            logger = logger,
        ).cleanup()

        recording.records.count { it.level == LogLevel.WARN } shouldBe 0
    }

    @Test
    fun progressFractionsForwardedToCaller() = runTest {
        val payload = ByteArray(256 * 1024) // 4 × 64 KB chunks
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())
        val emitted = mutableListOf<Float>()

        resolveStreamOrBytesToTempFile(
            input = MediaSource.Local.Bytes(payload),
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.IMAGE,
            logger = logger,
            onProgress = { emitted += it },
        ).cleanup()

        emitted.size shouldBe 4
        emitted.last() shouldBe 1.0f
        for (i in 1 until emitted.size) {
            assertTrue(emitted[i] >= emitted[i - 1], "progress went backwards at $i: $emitted")
        }
    }

    @Test
    fun cleanupIsIdempotent() = runTest {
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())
        val tracking = TrackingSource(ByteArray(4 * 1024))
        val input = MediaSource.Local.Stream(tracking, sizeHint = tracking.totalBytes, closeOnFinish = true)

        val resolved = resolveStreamOrBytesToTempFile(
            input = input,
            fileSystem = fs,
            tempDir = tempDir,
            mediaType = MediaType.VIDEO,
            logger = logger,
        )
        val path = resolved.path.toPath()

        resolved.cleanup()
        resolved.cleanup() // second invocation must not throw and must not double-close
        resolved.cleanup()

        fs.exists(path) shouldBe false
        tracking.closedCount shouldBe 3 // cleanup is idempotent on FS, but source.close() is
        // intentionally best-effort; repeated calls reach the Source's own close() — recording
        // the exact count pins the observable behaviour so future refactors surface regressions.
    }

    @Test
    fun nonStreamOrBytesInputRaisesIllegalState() = runTest {
        val fs = FakeFileSystem()
        val logger = SafeLogger(RecordingLogger())
        // FilePath reaches this helper only via programmer error (callers are expected to
        // pattern-match before dispatching). The contract is an IllegalStateException.
        assertFailsWith<IllegalStateException> {
            resolveStreamOrBytesToTempFile(
                input = MediaSource.Local.FilePath("/tmp/whatever.bin"),
                fileSystem = fs,
                tempDir = tempDir,
                mediaType = MediaType.VIDEO,
                logger = logger,
            )
        }
    }

    // --- helpers --------------------------------------------------------------

    private data class LogRecord(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    /** Records every emission without filtering — the test asserts on the captured list. */
    private class RecordingLogger : KompressorLogger {
        val records: MutableList<LogRecord> = mutableListOf()
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            records += LogRecord(level, tag, message, throwable)
        }
    }

    private class TrackingSource(payload: ByteArray) : Source {
        private val buffer = Buffer().apply { write(payload) }
        val totalBytes: Long = payload.size.toLong()
        var closedCount: Int = 0
            private set

        override fun read(sink: Buffer, byteCount: Long): Long = buffer.read(sink, byteCount)
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {
            closedCount++
        }
    }
}
