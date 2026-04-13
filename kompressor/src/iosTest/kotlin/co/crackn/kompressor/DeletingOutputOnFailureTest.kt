@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Contract tests for the iOS mirror of `deletingOutputOnFailure` (see
 * `DeletingOutputOnFailure.ios.kt`). The Android host-side test
 * (`androidHostTest/video/DeletingOutputOnFailureTest.kt`) covers the JVM implementation; this
 * file covers the K/N path through `NSFileManager.removeItemAtPath`.
 *
 * Key invariants:
 *   1. Block returns normally → output untouched.
 *   2. Block throws any `Throwable` → output file removed before the throw propagates.
 *   3. Block throws and the file **doesn't exist yet** (cancel before first sample write) →
 *      cleanup is a silent no-op; the throw still propagates. This is the "idempotent
 *      cleanup" invariant that item 8 of the audit called out.
 */
class DeletingOutputOnFailureTest {

    private lateinit var tempDir: String

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "deleting-output-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
    }

    @Test
    fun successLeavesOutputIntact() {
        val outputPath = tempDir + "ok.m4a"
        writeSmallBlob(outputPath)

        val result = deletingOutputOnFailure(outputPath) { "done" }

        assertEquals("done", result)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(outputPath))
    }

    @Test
    fun failureRemovesPartialOutput() {
        val outputPath = tempDir + "boom.m4a"
        writeSmallBlob(outputPath)

        assertFails {
            deletingOutputOnFailure<Unit>(outputPath) { error("boom") }
        }

        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(outputPath),
            "Cleanup must remove the partial output on throw",
        )
    }

    @Test
    fun failureWithNoOutputFile_stillRethrows_withNoError() {
        // Covers the "cancel before first sample write" path: the output hasn't been created
        // yet, cleanup is a no-op, and the original exception must still bubble out
        // untouched. Without this invariant, a race between cancel and
        // `AVAssetWriter.startWriting` could suppress the real reason the export failed.
        val outputPath = tempDir + "never_created.m4a"
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(outputPath))

        val ex = assertFails {
            deletingOutputOnFailure<Unit>(outputPath) { error("original reason") }
        }
        // Kotlin/Native wraps `error(...)` in `IllegalStateException`; message must survive.
        assertTrue(
            ex.message?.contains("original reason") == true,
            "Exception message lost through cleanup: ${ex.message}",
        )
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(outputPath))
    }

    @Test
    fun cancellationExceptionAlsoTriggersCleanup() {
        // CancellationException is specifically excluded from `runCatching`'s kotlinx-coroutines
        // behaviour but `deletingOutputOnFailure` must treat it like any other throw — partial
        // output is just as harmful whether the throw was "real error" or "user pressed cancel".
        // Assert the specific exception type + message so a regression that silently wraps /
        // swaps the exception doesn't slip through.
        val outputPath = tempDir + "cancelled.m4a"
        writeSmallBlob(outputPath)

        val ex = kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            deletingOutputOnFailure<Unit>(outputPath) {
                throw kotlinx.coroutines.CancellationException("user cancelled")
            }
        }
        assertEquals(
            "user cancelled",
            ex.message,
            "CancellationException must be re-thrown with original message intact",
        )

        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(outputPath),
            "CancellationException must also trigger partial-output cleanup",
        )
    }

    private fun writeSmallBlob(path: String) {
        // Empty-file write is enough for the cleanup assertion — we only need the file to
        // exist so `removeItemAtPath` has something to remove. `NSFileManager.createFile` is
        // the simplest K/N-friendly API for this.
        NSFileManager.defaultManager.createFileAtPath(
            path, contents = null, attributes = null,
        )
    }
}
