/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.content.ContentResolver
import android.content.ContentValues
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.MediaStore
import co.crackn.kompressor.logging.KompressorLogger
import co.crackn.kompressor.logging.LogLevel
import co.crackn.kompressor.logging.SafeLogger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Host-side tests for [MediaStoreOutputStrategy] — the IS_PENDING state-machine that gates
 * MediaStore writes on Android 10+.
 *
 * Host classpath is AGP's stubbed `android.jar`, so two pieces of Android framework need
 * explicit mock wiring:
 *  - [ContentValues] — SUT allocates two fresh instances (IS_PENDING=1, IS_PENDING=0) via
 *    `ContentValues()`. [mockkConstructor] replaces the empty constructor with a relaxed mock
 *    that returns default values for every call — we don't inspect the contents.
 *  - [ContentResolver], [Uri] — plain mockk mocks, not allocated by the SUT.
 *
 * Graceful-fallback logging goes through the library's [KompressorLogger] abstraction rather
 * than `android.util.Log`, so no static-log mocking is needed — the "was a WARN emitted?"
 * invariant is verified by passing a [RecordingLogger] instance and asserting on its capture.
 *
 * The lambda-based [MediaStoreOutputStrategy.withWriteStream] API replaces the earlier
 * `openForWrite` + auto-commit-on-close pattern per PR #141 review: clearing `IS_PENDING=0`
 * only after the block returns normally keeps half-written files out of the gallery on failure
 * paths.
 *
 * End-to-end round-trip (real `contentResolver.insert(...)` + IS_PENDING byte-level verification)
 * lives in `MediaStoreOutputEndToEndTest` in `androidDeviceTest`.
 */
class MediaStoreOutputStrategyTest {

    private lateinit var resolver: ContentResolver
    private lateinit var uri: Uri

    @BeforeTest
    fun setUp() {
        mockkConstructor(ContentValues::class)
        every { anyConstructed<ContentValues>().put(any<String>(), any<Int>()) } returns Unit
        resolver = mockk(relaxed = true)
        uri = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(ContentValues::class)
        unmockkAll()
        clearAllMocks()
    }

    /**
     * In-memory [KompressorLogger] used for the graceful-fallback tests. Appends each emission
     * to [records] so tests can assert on level + tag + message without needing to mock the
     * platform `android.util.Log` surface.
     */
    private class RecordingLogger : KompressorLogger {
        data class Record(val level: LogLevel, val tag: String, val message: String)

        val records: MutableList<Record> = mutableListOf()

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            records += Record(level, tag, message)
        }
    }

    @Test
    fun withWriteStreamSetsIsPendingBeforeOpeningStream() {
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        MediaStoreOutputStrategy.withWriteStream(resolver, uri) { /* no-op */ }

        // Ordering invariant: IS_PENDING=1 must land before `openOutputStream(uri)` returns —
        // inverting this would let the file become gallery-visible while still being written.
        verifyOrder {
            resolver.update(uri, any<ContentValues>(), null, null)
            resolver.openOutputStream(uri)
        }
    }

    @Test
    fun successfulBlockClearsIsPending() {
        val captured = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns captured

        MediaStoreOutputStrategy.withWriteStream(resolver, uri) { sink ->
            sink.write(byteArrayOf(1, 2, 3))
        }

        // Two `update` calls on the happy path: mark pending + clear pending.
        verify(exactly = 2) { resolver.update(uri, any<ContentValues>(), null, null) }
        captured.toByteArray() shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun throwingBlockDoesNotClearIsPending() {
        // PR #141 review, finding #1: a failure mid-copy must NOT flip IS_PENDING to 0.
        // Otherwise a half-written file becomes gallery-visible with a broken thumbnail.
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        shouldThrow<IllegalStateException> {
            MediaStoreOutputStrategy.withWriteStream(resolver, uri) { sink ->
                sink.write(byteArrayOf(1, 2))
                error("simulated mid-copy failure")
            }
        }

        // Exactly ONE update — the markPending(IS_PENDING=1) call. The clearPending(IS_PENDING=0)
        // update is NOT run because the block threw. The row stays invisible until the caller
        // either retries or issues contentResolver.delete(uri).
        verify(exactly = 1) { resolver.update(uri, any<ContentValues>(), null, null) }
    }

    @Test
    fun openOutputStreamThrowingRollsBackViaDelete() {
        // PR #141 review, finding #2: if openOutputStream throws AFTER markPending wrote
        // IS_PENDING=1, the row would otherwise be orphaned forever — invisible to the
        // gallery with no caller reference to clean it up. Rollback issues a best-effort delete.
        every { resolver.openOutputStream(uri) } throws FileNotFoundException("provider revoked access")

        shouldThrow<FileNotFoundException> {
            MediaStoreOutputStrategy.withWriteStream(resolver, uri) { /* never runs */ }
        }

        // markPending ran, then openOutputStream threw, then rollback delete(uri) ran.
        verifyOrder {
            resolver.update(uri, any<ContentValues>(), null, null)
            resolver.openOutputStream(uri)
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun withWriteStreamThrowsIllegalStateWhenResolverReturnsNull() {
        every { resolver.openOutputStream(uri) } returns null

        val e = shouldThrow<IllegalStateException> {
            MediaStoreOutputStrategy.withWriteStream(resolver, uri) { /* never runs */ }
        }

        e.message shouldBe "ContentResolver returned null OutputStream for ${uri}"
        // Null return still triggers orphan rollback — caller has no stream reference either.
        verify(exactly = 1) { resolver.delete(uri, null, null) }
    }

    @Test
    fun withWriteStreamSwallowsSqliteExceptionOnMarkPending() {
        every {
            resolver.update(uri, any<ContentValues>(), null, null)
        } throws SQLiteException("IS_PENDING column missing")
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()
        val recorder = RecordingLogger()

        // Graceful fallback: a custom provider aliasing MediaStore.AUTHORITY that doesn't
        // implement IS_PENDING must not block the write — and must leave a WARN trail so the
        // consumer's logger sees what was swallowed.
        MediaStoreOutputStrategy.withWriteStream(resolver, uri, SafeLogger(recorder)) {
            /* empty block — exercise the fallback path only */
        }

        verify(exactly = 1) { resolver.openOutputStream(uri) }
        val warns = recorder.records.filter { it.level == LogLevel.WARN }
        warns.isNotEmpty() shouldBe true
        warns.first().tag shouldBe "Kompressor.IO"
    }

    @Test
    fun withWriteStreamSwallowsIllegalArgumentExceptionOnMarkPending() {
        every {
            resolver.update(uri, any<ContentValues>(), null, null)
        } throws IllegalArgumentException("unknown column")
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        MediaStoreOutputStrategy.withWriteStream(resolver, uri) { /* no-op */ }

        verify(exactly = 1) { resolver.openOutputStream(uri) }
    }

    @Test
    fun withWriteStreamSwallowsSecurityExceptionOnMarkPending() {
        every {
            resolver.update(uri, any<ContentValues>(), null, null)
        } throws SecurityException("no write permission")
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        MediaStoreOutputStrategy.withWriteStream(resolver, uri) { /* no-op */ }

        verify(exactly = 1) { resolver.openOutputStream(uri) }
    }

    @Test
    fun swallowsFailuresOnClearPending() {
        // mark-pending succeeds but clear-pending fails — the compressed bytes are already on
        // disk by the time the success path reaches clearPending, so we log a WARN and let the
        // caller proceed. The block's return value is preserved.
        val callCount = intArrayOf(0)
        every { resolver.update(uri, any<ContentValues>(), null, null) } answers {
            if (callCount[0]++ == 0) 1 else throw SQLiteException("row gone")
        }
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        val result = MediaStoreOutputStrategy.withWriteStream(resolver, uri) { "ok" }

        result shouldBe "ok"
    }

    @Test
    fun writeBytesAreForwardedVerbatimToUnderlyingStream() {
        val sink = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns sink

        MediaStoreOutputStrategy.withWriteStream(resolver, uri) { out ->
            out.write(42) // single-byte overload
            out.write(byteArrayOf(1, 2, 3))
            out.write(byteArrayOf(4, 5, 6, 7, 8), 1, 3) // offset+length overload
            out.flush()
        }

        sink.toByteArray() shouldBe byteArrayOf(42, 1, 2, 3, 5, 6, 7)
    }

    @Test
    fun mediaStoreAuthorityConstantIsStableAtMedia() {
        // Pin the platform constant our `isMediaStoreUri` check depends on. If AOSP ever renamed
        // `MediaStore.AUTHORITY` this would catch it at host-test time instead of silently
        // degrading every MediaStore write to the non-pending fallback path.
        MediaStore.AUTHORITY shouldBe "media"
    }

    @Test
    fun isPendingColumnConstantIsStableAtIsPending() {
        // Sibling pin for the column name — `MediaStoreOutputStrategy` uses
        // `MediaStore.MediaColumns.IS_PENDING` literally; a rename here would silently turn the
        // mark-pending update into a no-op.
        MediaStore.MediaColumns.IS_PENDING shouldBe "is_pending"
    }
}
