/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.Sink
import okio.Timeout
import okio.fakefilesystem.FakeFileSystem

/**
 * commonTest coverage for [OutputSink], [StreamOutputSink], and [createStreamOutputSink].
 * Runs against `FakeFileSystem` via the injectable overloads added to [OutputSink.kt] so the
 * suite is pure Kotlin.
 */
class OutputSinkTest {

    private val tempDir = "/tmp/kompressor-sink-test".toPath()

    @Test
    fun fileOutputSinkIsEntirelyNoOp() = runTest {
        val sink = FileOutputSink("/tmp/out.bin")
        sink.tempPath shouldBe "/tmp/out.bin"
        sink.publish { error("progress callback must not fire for FileOutputSink") }
        sink.cleanup() // must not throw
        sink.cleanup() // idempotent
    }

    @Test
    fun streamOutputSinkPublishesIdenticalBytesIntoConsumer() = runTest {
        val payload = ByteArray(200 * 1024) { (it % 256).toByte() }
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "input.bin"
        fs.write(tempFile) { write(payload) }
        val consumer = Buffer()

        val outSink = StreamOutputSink(
            tempFile = tempFile,
            sink = consumer,
            closeOnFinish = true,
            fileSystem = fs,
        )
        outSink.publish()

        consumer.readByteArray().contentEquals(payload) shouldBe true
    }

    @Test
    fun streamOutputSinkProgressIsMonotonicAndEndsAtOne() = runTest {
        // 256 KB payload big enough to guarantee >= 2 chunks regardless of the
        // `SINK_COPY_BUFFER_SIZE` constant. Asserting only "at least 2 ticks" keeps this
        // test decoupled from the chunk-size tuning knob — the real invariants pinned here
        // are `last() == 1.0f`, monotonic non-decreasing, and `[0f, 1f]` bounded.
        val payload = ByteArray(256 * 1024)
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "input.bin"
        fs.write(tempFile) { write(payload) }
        val emitted = mutableListOf<Float>()

        StreamOutputSink(tempFile, Buffer(), closeOnFinish = true, fileSystem = fs)
            .publish { emitted += it }

        assertTrue(emitted.size >= 2, "expected at least 2 progress emissions, got ${emitted.size}")
        emitted.last() shouldBe 1.0f
        for (i in 1 until emitted.size) {
            assertTrue(emitted[i] >= emitted[i - 1], "progress went backwards: $emitted")
        }
        emitted.forEach { assertTrue(it in 0f..1f, "fraction out of range: $it") }
    }

    @Test
    fun streamOutputSinkPublishDoesNotCloseConsumerSink() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "input.bin"
        fs.write(tempFile) { write(ByteArray(32 * 1024)) }
        val consumer = TrackingSink()

        StreamOutputSink(tempFile, consumer, closeOnFinish = false, fileSystem = fs).publish()

        // `publish` only flushes — it must never close the consumer sink, regardless of
        // closeOnFinish. `cleanup()` is the sole close gateway (see [closeOnFinish] contract).
        consumer.closedCount shouldBe 0
    }

    @Test
    fun cleanupWithCloseOnFinishTrueClosesConsumerSinkAndDeletesTemp() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "input.bin"
        fs.write(tempFile) { write(ByteArray(4 * 1024)) }
        val consumer = TrackingSink()

        val outSink = StreamOutputSink(tempFile, consumer, closeOnFinish = true, fileSystem = fs)
        outSink.publish()
        outSink.cleanup()

        fs.exists(tempFile) shouldBe false
        consumer.closedCount shouldBe 1
    }

    @Test
    fun cleanupWithCloseOnFinishFalseDeletesTempButLeavesConsumerOpen() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "input.bin"
        fs.write(tempFile) { write(ByteArray(4 * 1024)) }
        val consumer = TrackingSink()

        val outSink = StreamOutputSink(tempFile, consumer, closeOnFinish = false, fileSystem = fs)
        outSink.publish()
        outSink.cleanup()

        fs.exists(tempFile) shouldBe false
        consumer.closedCount shouldBe 0
    }

    @Test
    fun cleanupIsIdempotent() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "input.bin"
        fs.write(tempFile) { write(ByteArray(1024)) }
        val consumer = TrackingSink()

        val outSink = StreamOutputSink(tempFile, consumer, closeOnFinish = true, fileSystem = fs)
        outSink.cleanup()
        outSink.cleanup() // second cleanup must not throw
        outSink.cleanup()

        fs.exists(tempFile) shouldBe false
    }

    @Test
    fun emptyTempFilePublishesCleanly() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "empty.bin"
        fs.write(tempFile) { /* intentionally empty */ }
        val consumer = Buffer()
        val emitted = mutableListOf<Float>()

        StreamOutputSink(tempFile, consumer, closeOnFinish = true, fileSystem = fs)
            .publish { emitted += it }

        consumer.size shouldBe 0L
        // No chunks copied → no progress callbacks fired. Still a valid publish.
        emitted.size shouldBe 0
    }

    @Test
    fun createStreamOutputSinkDoesNotPreCreateTempFile() {
        // AVAssetExportSession on iOS refuses to overwrite an existing file, so the factory
        // must reserve a path but leave the file absent until the compressor writes to it.
        val fs = FakeFileSystem()
        val destination = MediaDestination.Local.Stream(Buffer())

        val outSink = createStreamOutputSink(destination, fs, tempDir)

        fs.exists(outSink.tempPath.toPath()) shouldBe false
    }

    @Test
    fun createStreamOutputSinkCreatesMissingTempDir() {
        val fs = FakeFileSystem()
        val fresh = "/freshly/spawned/dir".toPath()
        fs.exists(fresh) shouldBe false
        val destination = MediaDestination.Local.Stream(Buffer())

        createStreamOutputSink(destination, fs, fresh)

        fs.exists(fresh) shouldBe true
    }

    @Test
    fun createStreamOutputSinkNamesTempFileWithKmpOutPattern() {
        val fs = FakeFileSystem()
        val destination = MediaDestination.Local.Stream(Buffer())

        val outSink = createStreamOutputSink(destination, fs, tempDir)

        val name = outSink.tempPath.toPath().name
        assertTrue(name.startsWith("kmp_out_"), "unexpected prefix in $name")
        assertTrue(name.endsWith(".bin"), "unexpected suffix in $name")
    }

    // --- helpers --------------------------------------------------------------

    /** Sink that records whether [close] was ever invoked, for lifecycle assertions. */
    private class TrackingSink : Sink {
        private val discard = Buffer()
        var closedCount: Int = 0
            private set

        override fun write(source: Buffer, byteCount: Long) {
            // Consume the bytes without retaining so large payloads don't bloat the heap
            // mid-test. The content is validated in [streamOutputSinkPublishesIdenticalBytesIntoConsumer]
            // via a pure `Buffer` — this TrackingSink exists only to observe close().
            source.read(discard, byteCount)
            discard.clear()
        }

        override fun flush() = Unit
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {
            closedCount++
        }
    }
}
