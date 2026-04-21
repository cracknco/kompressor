/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.matchers.shouldBe
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.Buffer
import okio.Path.Companion.toPath
import okio.Source
import okio.Timeout
import okio.fakefilesystem.FakeFileSystem

/**
 * commonTest coverage for `TempFileMaterializer`. All assertions run against
 * `FakeFileSystem` so the suite is pure Kotlin with no platform FS dependencies.
 */
class TempFileMaterializerTest {

    private val tempDir = "/tmp/kompressor-io-test".toPath()

    @Test
    fun materializeOneMibStreamProducesIdenticalContent() = runTest {
        val payload = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val fs = FakeFileSystem()
        val source = Buffer().apply { write(payload) }

        val tempFile = source.materializeToTempFile(fs, tempDir, sizeHint = payload.size.toLong())

        fs.exists(tempFile) shouldBe true
        fs.metadata(tempFile).size shouldBe payload.size.toLong()
        val written = fs.read(tempFile) { readByteArray() }
        written.contentEquals(payload) shouldBe true
    }

    @Test
    fun progressFractionsAreMonotonicAndReachOneWithSizeHint() = runTest {
        // 256 KB across 4 * 64 KB chunks — predictable progress steps.
        val payload = ByteArray(256 * 1024)
        val fs = FakeFileSystem()
        val emitted = mutableListOf<Float>()

        Buffer().apply { write(payload) }
            .materializeToTempFile(fs, tempDir, sizeHint = payload.size.toLong()) { emitted += it }

        emitted.size shouldBe 4
        emitted.last() shouldBe 1.0f
        for (i in 1 until emitted.size) {
            assertTrue(emitted[i] >= emitted[i - 1], "progress went backwards at $i: $emitted")
        }
        // The spec clamps fractions to [0, 1] — assert the lower bound too.
        emitted.forEach { assertTrue(it in 0f..1f, "fraction out of range: $it in $emitted") }
    }

    @Test
    fun progressFractionIsZeroWhenSizeHintIsNull() = runTest {
        val payload = ByteArray(256 * 1024)
        val fs = FakeFileSystem()
        val emitted = mutableListOf<Float>()

        Buffer().apply { write(payload) }
            .materializeToTempFile(fs, tempDir, sizeHint = null) { emitted += it }

        emitted.size shouldBe 4
        emitted.forEach { it shouldBe 0f }
    }

    @Test
    fun progressFractionIsZeroWhenSizeHintIsZero() = runTest {
        // `MediaSource.Local.Stream(sizeHint = 0)` is legal per CRA-90. With a positive
        // byte payload the fraction would blow up on divide-by-zero if the primitive did
        // not guard for this — so exercise the guard explicitly.
        val payload = ByteArray(64 * 1024)
        val fs = FakeFileSystem()
        val emitted = mutableListOf<Float>()

        Buffer().apply { write(payload) }
            .materializeToTempFile(fs, tempDir, sizeHint = 0L) { emitted += it }

        emitted.forEach { it shouldBe 0f }
    }

    @Test
    fun fractionIsClampedWhenSizeHintUnderestimatesRealSize() = runTest {
        // Real source = 128 KB but caller advertises 64 KB. After the second chunk the
        // raw ratio is 2.0; the contract requires clamping to 1.0.
        val payload = ByteArray(128 * 1024)
        val fs = FakeFileSystem()
        val emitted = mutableListOf<Float>()

        Buffer().apply { write(payload) }
            .materializeToTempFile(fs, tempDir, sizeHint = 64L * 1024L) { emitted += it }

        emitted.forEach { assertTrue(it <= 1f, "fraction exceeded 1f: $it") }
        emitted.last() shouldBe 1.0f
    }

    @Test
    fun emptySourceCreatesEmptyTempFile() = runTest {
        val fs = FakeFileSystem()
        val tempFile = Buffer().materializeToTempFile(fs, tempDir, sizeHint = 0L)

        fs.exists(tempFile) shouldBe true
        fs.metadata(tempFile).size shouldBe 0L
    }

    @Test
    fun sourceIsNotClosedByMaterialization() = runTest {
        val fs = FakeFileSystem()
        val tracking = TrackingSource(ByteArray(32 * 1024))

        tracking.materializeToTempFile(fs, tempDir, sizeHint = tracking.totalBytes)

        tracking.closedCount shouldBe 0
    }

    @Test
    fun createsTempDirIfMissing() = runTest {
        val fs = FakeFileSystem()
        val nestedDir = "/freshly/created/dir".toPath()
        fs.exists(nestedDir) shouldBe false

        val path = Buffer().apply { writeUtf8("hello") }
            .materializeToTempFile(fs, nestedDir, sizeHint = 5L)

        fs.exists(nestedDir) shouldBe true
        fs.exists(path) shouldBe true
    }

    @Test
    fun tempFileNameMatchesKmpIoPattern() = runTest {
        val fs = FakeFileSystem()
        val path = Buffer().apply { writeUtf8("x") }
            .materializeToTempFile(fs, tempDir, sizeHint = 1L)

        val name = path.name
        assertTrue(name.startsWith("kmp_io_"), "unexpected prefix in $name")
        assertTrue(name.endsWith(".bin"), "unexpected suffix in $name")
    }

    @Test
    fun cancellationDeletesTempFileAndRethrows() = runTest {
        val fs = FakeFileSystem()
        // Large enough source to guarantee many chunks. We cancel the job from the
        // `onProgress` callback itself (a suspension point) — this reliably interleaves
        // the cancel signal with the copy loop under runTest's single-threaded scheduler:
        // the next iteration's `ensureActive()` trips and the catch-rethrow path runs.
        val source = Buffer().apply { write(ByteArray(1024 * 1024)) } // 16 chunks of 64 KB
        val reachedFirstChunk = CompletableDeferred<Unit>()

        assertFailsWith<CancellationException> {
            coroutineScope {
                val job = async {
                    source.materializeToTempFile(fs, tempDir, sizeHint = source.size) {
                        reachedFirstChunk.complete(Unit)
                        // Yield so the outer scope gets a chance to fire `cancel`.
                        yield()
                    }
                }
                reachedFirstChunk.await()
                job.cancel()
                job.await() // rethrows CancellationException
            }
        }

        // No temp file should be left behind. The materializer creates files under
        // `tempDir` with the `kmp_io_*.bin` pattern — assert the directory either does
        // not exist or contains no such file.
        val residual = if (fs.exists(tempDir)) {
            fs.list(tempDir).count { it.name.startsWith("kmp_io_") }
        } else {
            0
        }
        residual shouldBe 0
    }

    @Test
    fun ioFailureDuringCopyCleansUpTempFile() = runTest {
        val fs = FakeFileSystem()
        val boom = FailingSource(bytesBeforeFailure = 64 * 1024)

        assertFailsWith<RuntimeException> {
            boom.materializeToTempFile(fs, tempDir, sizeHint = 128L * 1024L)
        }

        val residual = if (fs.exists(tempDir)) {
            fs.list(tempDir).count { it.name.startsWith("kmp_io_") }
        } else {
            0
        }
        residual shouldBe 0
    }

    // --- helpers --------------------------------------------------------------

    /** Source that records whether [close] was ever invoked. */
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

    /** Source that serves one chunk then fails — exercises the non-cancellation cleanup path. */
    private class FailingSource(private val bytesBeforeFailure: Int) : Source {
        private var served = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (served >= bytesBeforeFailure) error("simulated read failure")
            val chunk = minOf(byteCount, (bytesBeforeFailure - served).toLong())
            sink.write(ByteArray(chunk.toInt()))
            served += chunk.toInt()
            return chunk
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }

}
