/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import co.crackn.kompressor.KompressorContext
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Host-side unit tests for the Android actual of [estimateSourceSize].
 *
 * Platform handles (`Uri`, `ContentResolver`, `Cursor`, `ParcelFileDescriptor`) are mocked via
 * MockK — the host test classpath uses the stubbed `android.jar` from AGP which throws
 * `"Stub!"` on static framework calls. Robolectric is not used in this project; MockK is the
 * house pattern (see `AndroidMediaSourcesTest`).
 *
 * [KompressorContext] is reset between tests and re-initialised with a mocked application
 * `Context` whose `contentResolver` returns the mocked resolver — this is the seam the probe
 * consults via `KompressorContext.appContext.contentResolver`.
 */
class SourceSizeProbeAndroidTest {

    private lateinit var resolver: ContentResolver

    @BeforeTest
    fun setup() {
        resolver = mockk()
        val appCtx = mockk<Context>()
        every { appCtx.applicationContext } returns appCtx
        every { appCtx.contentResolver } returns resolver
        KompressorContext.resetForTest()
        KompressorContext.init(appCtx)
    }

    @AfterTest
    fun tearDown() {
        KompressorContext.resetForTest()
    }

    // ── FilePath ──────────────────────────────────────────────────────────────

    @Test
    fun filePathReportsFileLengthWhenExists() {
        val tmp = File.createTempFile("kmp_probe_", ".bin").apply {
            writeBytes(ByteArray(777) { 0 })
            deleteOnExit()
        }
        estimateSourceSize(MediaSource.Local.FilePath(tmp.absolutePath)) shouldBe 777L
    }

    @Test
    fun filePathReportsNullWhenMissing() {
        val missing = "/nonexistent/absolutely/not/there-${System.nanoTime()}.bin"
        estimateSourceSize(MediaSource.Local.FilePath(missing)) shouldBe null
    }

    // ── AndroidUriMediaSource via OpenableColumns.SIZE ────────────────────────

    @Test
    fun uriReadsOpenableColumnsSize() {
        val uri = mockk<Uri>()
        val cursor = mockOpenableSizeCursor(sizeValue = 4096L)
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } returns cursor

        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe 4096L
    }

    @Test
    fun uriFallsBackToPfdStatSizeWhenQueryReturnsNull() {
        val uri = mockk<Uri>()
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } returns null
        val pfd = mockk<ParcelFileDescriptor>()
        every { pfd.statSize } returns 2048L
        every { pfd.close() } returns Unit
        every { resolver.openFileDescriptor(uri, "r") } returns pfd

        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe 2048L
    }

    @Test
    fun uriReturnsNullWhenCursorEmpty() {
        val uri = mockk<Uri>()
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns false
        every { cursor.close() } returns Unit
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } returns cursor
        // Fallback path also exhausted.
        every { resolver.openFileDescriptor(uri, "r") } returns null

        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe null
    }

    @Test
    fun uriReturnsNullWhenColumnIndexNegative() {
        val uri = mockk<Uri>()
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns -1
        every { cursor.close() } returns Unit
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } returns cursor
        every { resolver.openFileDescriptor(uri, "r") } returns null

        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe null
    }

    @Test
    fun uriReturnsNullWhenColumnIsNull() {
        val uri = mockk<Uri>()
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 0
        every { cursor.isNull(0) } returns true
        every { cursor.close() } returns Unit
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } returns cursor
        every { resolver.openFileDescriptor(uri, "r") } returns null

        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe null
    }

    @Test
    fun uriNegativeSizeFallsBackToPfd() {
        val uri = mockk<Uri>()
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 0
        every { cursor.isNull(0) } returns false
        every { cursor.getLong(0) } returns -1L // treated as "unknown"
        every { cursor.close() } returns Unit
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } returns cursor
        val pfd = mockk<ParcelFileDescriptor>()
        every { pfd.statSize } returns 9999L
        every { pfd.close() } returns Unit
        every { resolver.openFileDescriptor(uri, "r") } returns pfd

        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe 9999L
    }

    @Test
    fun uriReturnsNullWhenQueryThrowsSecurityException() {
        val uri = mockk<Uri>()
        every {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } throws SecurityException("permission denied")
        every { resolver.openFileDescriptor(uri, "r") } returns null

        // runCatching wrapper converts the SecurityException to a silent null.
        estimateSourceSize(AndroidUriMediaSource(uri)) shouldBe null
    }

    // ── AndroidPfdMediaSource ─────────────────────────────────────────────────

    @Test
    fun pfdReportsStatSize() {
        val pfd = mockk<ParcelFileDescriptor>()
        every { pfd.statSize } returns 12345L

        estimateSourceSize(AndroidPfdMediaSource(pfd)) shouldBe 12345L
    }

    @Test
    fun pfdReturnsNullWhenStatSizeNegative() {
        // Pipes and sockets surface statSize == -1. The probe contract forbids returning
        // negatives; -1 → null is explicit.
        val pfd = mockk<ParcelFileDescriptor>()
        every { pfd.statSize } returns -1L

        estimateSourceSize(AndroidPfdMediaSource(pfd)) shouldBe null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** MockK-backed `Cursor` that returns [sizeValue] for `OpenableColumns.SIZE` at index 0. */
    private fun mockOpenableSizeCursor(sizeValue: Long): Cursor = mockk {
        every { moveToFirst() } returns true
        every { getColumnIndex(OpenableColumns.SIZE) } returns 0
        every { isNull(0) } returns false
        every { getLong(0) } returns sizeValue
        every { close() } returns Unit
    }
}
