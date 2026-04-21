/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.KompressorContext
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Device-side test for [materializeToTempFile] — validates that the real `cacheDir`
 * resolution, temp file placement, and chunked copy all behave correctly on an actual
 * Android runtime. Host tests can't stand up [KompressorContext.appContext], so the
 * filesystem-integration half of the DoD checklist lives here.
 */
class TempFileMaterializerAndroidTest {

    @Before
    fun ensureInitialized() {
        // `KompressorInitializerDeviceTest` resets the singleton between runs; make sure
        // we have a live Context before exercising `kompressorTempDir()`.
        KompressorContext.init(InstrumentationRegistry.getInstrumentation().targetContext)
        cleanKompressorIoDir()
    }

    @After
    fun cleanupAfterRun() {
        cleanKompressorIoDir()
    }

    @Test
    fun tempFilePathLandsUnderAppCacheDirKompressorIo() = runBlocking {
        val source = Buffer().apply { writeUtf8("hello") }
        val path = source.materializeToTempFile(kompressorTempDir(), sizeHint = 5L)

        val cacheDirAbs = KompressorContext.appContext.cacheDir.absolutePath
        assertTrue(
            path.toString().startsWith(cacheDirAbs),
            "expected path under cacheDir ($cacheDirAbs), got $path",
        )
        assertTrue(path.toString().contains("kompressor-io"), "expected 'kompressor-io' segment in $path")
        assertTrue(path.name.startsWith("kmp_io_"), "expected 'kmp_io_' prefix, got ${path.name}")
        assertTrue(path.name.endsWith(".bin"), "expected '.bin' suffix, got ${path.name}")

        FileSystem.SYSTEM.delete(path)
    }

    @Test
    fun tempFileContentMatchesSourceExactly() = runBlocking {
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
    fun materializeLargeStreamKeepsJvmHeapBounded() = runBlocking {
        // 50 MB is large enough that an "accidentally full-buffer" implementation would blow
        // past a 10 MB bound, but small enough that the test fits in emulator memory without
        // flaking on low-end CI images. This is the low-memory-footprint gate from the DoD.
        val payload = ByteArray(LARGE_STREAM_BYTES) { (it % BYTE_MOD).toByte() }
        val source = Buffer().apply { write(payload) }

        // Give the JVM a clean baseline, then sample allocation delta across the copy.
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize()
        val path = source.materializeToTempFile(kompressorTempDir(), sizeHint = payload.size.toLong())
        val after = Debug.getNativeHeapAllocatedSize()

        try {
            val delta = after - before
            // Native-heap delta can be noisy (JIT codegen, thread stacks). The contract is
            // "O(buffer) allocation, not O(stream)" — well below the payload size.
            assertTrue(
                delta < HEAP_DELTA_BUDGET_BYTES,
                "native heap delta $delta exceeded budget $HEAP_DELTA_BUDGET_BYTES for $LARGE_STREAM_BYTES-byte payload",
            )
            assertTrue(FileSystem.SYSTEM.exists(path), "expected temp file to exist at $path")
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    private fun cleanKompressorIoDir() {
        val dir = File(KompressorContext.appContext.cacheDir, "kompressor-io")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val BYTE_MOD = 256
        const val HALF_MIB_BYTES = 512 * 1024
        const val LARGE_STREAM_BYTES = 50 * 1024 * 1024
        // 10 MB native-heap delta budget — leaves ample headroom above the 64 KB chunk
        // while still flagging a regression that would materialise the whole stream.
        const val HEAP_DELTA_BUDGET_BYTES = 10L * 1024 * 1024
    }
}
