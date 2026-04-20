/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

import co.crackn.kompressor.createKompressor
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Verifies the `createKompressor(logger = ...)` factory actually plumbs the supplied logger
 * through [SafeLogger] into the probe pipeline — the acceptance test for the pluggable logger
 * public API on Android.
 *
 * We drive `probe()` with a guaranteed-invalid path so the failure branch fires (android host
 * stubs reject the native [android.media.MediaMetadataRetriever] call anyway), then assert the
 * recorder saw the library's tagged WARN emission. That's the full integration proof:
 * factory → `AndroidKompressor` → `SafeLogger` → custom [KompressorLogger]. Neither
 * `KompressorContext` nor `MediaCodecList` is exercised on the probe-only path, so we can run
 * this test without a real Android runtime.
 */
class LoggerInjectionTest {

    @Test
    fun createKompressor_withCustomLogger_routesProbeFailureToRecorder(): Unit = runBlocking {
        val recorder = RecordingLogger()
        val kompressor = createKompressor(logger = recorder)

        val result = kompressor.probe("/does/not/exist.mp4")

        // Whether MediaMetadataRetriever reports "not found" or the host stub rejects the call is
        // immaterial — either way the probe wrapper must catch, log the failure, and return a
        // Result.failure. The logger observation is the contract, not the underlying error shape.
        result.isFailure shouldBe true

        recorder.records.shouldNotBeEmpty()
        // The probe lifecycle emits at minimum a DEBUG start and a WARN failure, both under the
        // Kompressor.Probe tag. We don't pin message text (not public contract) — only that the
        // injected logger is the one receiving them.
        val probeRecords = recorder.records.filter { it.tag == LogTags.PROBE }
        probeRecords.any { it.level == LogLevel.DEBUG } shouldBe true
        probeRecords.any { it.level == LogLevel.WARN } shouldBe true
    }

    @Test
    fun createKompressor_default_installsPlatformLoggerWithoutThrowing(): Unit = runBlocking {
        // No-arg factory installs PlatformLogger. We can't observe Logcat on host, but we can
        // prove the factory doesn't throw and that a probe failure propagates through
        // `android.util.Log` → `SafeLogger` without leaking the host stub's RuntimeException —
        // if the SafeLogger guard regressed, the RuntimeException from the stubbed Log.w would
        // crash this test.
        val kompressor = createKompressor()

        val result = kompressor.probe("/does/not/exist.mp4")
        result.isFailure shouldBe true
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
