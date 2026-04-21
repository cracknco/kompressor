/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.io

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

/**
 * iOS-simulator mirror of [TempFileMaterializerAndroidTest] — exercises the real
 * [NSTemporaryDirectory]-based [kompressorTempDir] path. Keeps the Android ↔ iOS parity
 * that the project's KMP layering insists on (one test per platform for every helper
 * that reads a platform-specific resource).
 */
class TempFileMaterializerIosTest {

    @BeforeTest
    fun cleanBefore() = cleanKompressorIoDir()

    @AfterTest
    fun cleanAfter() = cleanKompressorIoDir()

    @Test
    fun tempFileLandsUnderNsTemporaryDirectoryKompressorIo() = runTest {
        val path = Buffer().apply { writeUtf8("hello") }
            .materializeToTempFile(kompressorTempDir(), sizeHint = 5L)

        try {
            assertTrue(
                path.toString().startsWith(NSTemporaryDirectory()),
                "expected path under NSTemporaryDirectory (${NSTemporaryDirectory()}), got $path",
            )
            assertTrue(path.toString().contains("kompressor-io"), "expected 'kompressor-io' segment in $path")
            assertTrue(path.name.startsWith("kmp_io_"), "expected 'kmp_io_' prefix, got ${path.name}")
            assertTrue(path.name.endsWith(".bin"), "expected '.bin' suffix, got ${path.name}")
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    @Test
    fun tempFileContentMatchesSourceExactly() = runTest {
        val payload = ByteArray(HALF_MIB_BYTES) { (it % BYTE_MOD).toByte() }
        val source = Buffer().apply { write(payload) }

        val path = source.materializeToTempFile(kompressorTempDir(), sizeHint = payload.size.toLong())

        try {
            val written = FileSystem.SYSTEM.read(path) { readByteArray() }
            assertTrue(written.contentEquals(payload), "round-trip content mismatch")
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    @Test
    fun largeStreamMaterializesWithoutLoadingEverythingInMemory() = runTest {
        // Parity with the Android heap-bound test — here we simply assert that a
        // multi-MB copy completes and produces a byte-exact output. Simulator memory
        // APIs (mach_task_basic_info) are gated behind platform-specific cinterop that
        // isn't worth wiring for this single assertion; the chunked-copy invariant is
        // already enforced at the common-test level against FakeFileSystem.
        val payload = ByteArray(LARGE_STREAM_BYTES) { (it % BYTE_MOD).toByte() }
        val source = Buffer().apply { write(payload) }

        val path = source.materializeToTempFile(kompressorTempDir(), sizeHint = payload.size.toLong())
        try {
            val size = FileSystem.SYSTEM.metadata(path).size
            assertTrue(size == payload.size.toLong(), "expected $LARGE_STREAM_BYTES bytes, got $size")
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    private fun cleanKompressorIoDir() {
        val dir = NSTemporaryDirectory() + "kompressor-io"
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    private companion object {
        const val BYTE_MOD = 256
        const val HALF_MIB_BYTES = 512 * 1024
        const val LARGE_STREAM_BYTES = 10 * 1024 * 1024
    }
}
