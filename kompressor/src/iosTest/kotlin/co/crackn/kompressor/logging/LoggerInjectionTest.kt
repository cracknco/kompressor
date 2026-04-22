/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

import co.crackn.kompressor.createKompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the `createKompressor(logger = ...)` factory actually plumbs the supplied logger
 * through [SafeLogger] into the library's instrumented entry points — the acceptance test for
 * the pluggable logger public API on iOS.
 *
 * The iOS probe path is lenient (AVURLAsset on a non-existent file returns a zero-length asset
 * without throwing), so the failure-path integration proof instead runs an `image.compress()`
 * against a guaranteed-missing source. `instrumentCompress` emits an INFO start line and an
 * ERROR on the UIImage load failure — both under [LogTags.IMAGE].
 */
class LoggerInjectionTest {

    @Test
    fun createKompressor_withCustomLogger_routesImageFailureToRecorder() = runTest {
        val recorder = RecordingLogger()
        val kompressor = createKompressor(logger = recorder)

        val result = kompressor.image.compress(
            MediaSource.Local.FilePath("/does/not/exist.jpg"),
            MediaDestination.Local.FilePath("/tmp/kompressor-injection-ios-out.jpg"),
        )

        assertTrue(result.isFailure, "Nonexistent input must surface as Result.failure")

        val imageRecords = recorder.records.filter { it.tag == LogTags.IMAGE }
        // instrumentCompress emits INFO at start; the UIImage load failure emits ERROR.
        assertTrue(
            imageRecords.any { it.level == LogLevel.INFO },
            "Expected INFO start log under Kompressor.Image, got: ${recorder.records}",
        )
        assertTrue(
            imageRecords.any { it.level == LogLevel.ERROR },
            "Expected ERROR failure log under Kompressor.Image, got: ${recorder.records}",
        )
    }

    @Test
    fun createKompressor_default_installsPlatformLoggerWithoutThrowing() = runTest {
        // No-arg factory installs PlatformLogger (NSLog-backed). Any regression that lets a
        // platform-logger exception escape SafeLogger would fail this test, since the simulator
        // still evaluates NSLog even when no Console reader is attached.
        val kompressor = createKompressor()

        val result = kompressor.image.compress(
            MediaSource.Local.FilePath("/does/not/exist.jpg"),
            MediaDestination.Local.FilePath("/tmp/kompressor-injection-ios-default-out.jpg"),
        )
        assertTrue(result.isFailure)
    }

    private data class Record(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private class RecordingLogger : KompressorLogger {
        val records = mutableListOf<Record>()

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            records += Record(level, tag, message, throwable)
        }
    }
}
