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
 * Content assertions on the `ContentValues` objects themselves are intentionally absent: the
 * value-setting logic lives entirely inside the SUT (literal `put(IS_PENDING, 1)` / `put(..., 0)`
 * calls) and is obvious on inspection. Runtime content verification would require stubbing
 * `ContentValues.get(...)` to return what was `put(...)` — non-trivial given each mocked
 * constructor returns a distinct instance — and would not catch any realistic regression.
 * Ordering and call count are the meaningful invariants and are verified below.
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
    fun openForWriteSetsIsPendingBeforeOpeningStream() {
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        MediaStoreOutputStrategy.openForWrite(resolver, uri)

        // Ordering invariant: IS_PENDING=1 must land before `openOutputStream(uri)` returns —
        // inverting this would let the file become gallery-visible while still being written.
        verifyOrder {
            resolver.update(uri, any<ContentValues>(), null, null)
            resolver.openOutputStream(uri)
        }
    }

    @Test
    fun closedStreamClearsIsPending() {
        val captured = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns captured

        val stream = MediaStoreOutputStrategy.openForWrite(resolver, uri)
        stream.write(byteArrayOf(1, 2, 3))
        stream.close()

        // Two `update` calls on the happy path: mark pending + clear pending.
        verify(exactly = 2) { resolver.update(uri, any<ContentValues>(), null, null) }
        captured.toByteArray() shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun doubleCloseClearsIsPendingExactlyOnce() {
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        val stream = MediaStoreOutputStrategy.openForWrite(resolver, uri)
        stream.close()
        stream.close()

        // Idempotence guard: try-with-resources wrappers can call `close()` twice; the
        // IS_PENDING release is a mutation that must not re-run — it would hit a row the
        // caller may have already `contentResolver.delete(...)`ed.
        verify(exactly = 2) { resolver.update(uri, any<ContentValues>(), null, null) }
    }

    @Test
    fun openForWriteThrowsIllegalStateWhenResolverReturnsNull() {
        every { resolver.openOutputStream(uri) } returns null

        val e = shouldThrow<IllegalStateException> { MediaStoreOutputStrategy.openForWrite(resolver, uri) }

        e.message shouldBe "ContentResolver returned null OutputStream for ${uri}"
    }

    @Test
    fun openForWriteSwallowsSqliteExceptionOnMarkPending() {
        every {
            resolver.update(uri, any<ContentValues>(), null, null)
        } throws SQLiteException("IS_PENDING column missing")
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()
        val recorder = RecordingLogger()

        // Graceful fallback: a custom provider aliasing MediaStore.AUTHORITY that doesn't
        // implement IS_PENDING must not block the write — and must leave a WARN trail so the
        // consumer's logger sees what was swallowed.
        MediaStoreOutputStrategy.openForWrite(resolver, uri, SafeLogger(recorder))

        verify(exactly = 1) { resolver.openOutputStream(uri) }
        val warns = recorder.records.filter { it.level == LogLevel.WARN }
        warns.isNotEmpty() shouldBe true
        warns.first().tag shouldBe "Kompressor.IO"
    }

    @Test
    fun openForWriteSwallowsIllegalArgumentExceptionOnMarkPending() {
        every {
            resolver.update(uri, any<ContentValues>(), null, null)
        } throws IllegalArgumentException("unknown column")
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        MediaStoreOutputStrategy.openForWrite(resolver, uri)

        verify(exactly = 1) { resolver.openOutputStream(uri) }
    }

    @Test
    fun openForWriteSwallowsSecurityExceptionOnMarkPending() {
        every {
            resolver.update(uri, any<ContentValues>(), null, null)
        } throws SecurityException("no write permission")
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        MediaStoreOutputStrategy.openForWrite(resolver, uri)

        verify(exactly = 1) { resolver.openOutputStream(uri) }
    }

    @Test
    fun closeSwallowsFailuresOnClearPending() {
        // mark-pending succeeds but clear-pending fails — the compressed bytes are already on
        // disk by the time close() runs, so we log a WARN and let the caller proceed.
        val callCount = intArrayOf(0)
        every { resolver.update(uri, any<ContentValues>(), null, null) } answers {
            if (callCount[0]++ == 0) 1 else throw SQLiteException("row gone")
        }
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        val stream = MediaStoreOutputStrategy.openForWrite(resolver, uri)

        // Must not throw — bytes are already durable, swallowing a failed clear keeps the
        // caller's `Result.success` intact.
        stream.close()
    }

    @Test
    fun writeBytesAreForwardedVerbatimToUnderlyingStream() {
        val sink = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns sink

        MediaStoreOutputStrategy.openForWrite(resolver, uri).use { out ->
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
