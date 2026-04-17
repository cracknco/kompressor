/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.cinterop.spawn.kmp_posix_spawn
import co.crackn.kompressor.cinterop.spawn.kmp_waitpid_exit
import co.crackn.kompressor.testutil.MinimalPngFixtures
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * iOS inter-process regression guard for CRA-80 — closes the gap documented in
 * `docs/threading-model.md#ios-inter-process-coverage--known-gap`.
 *
 * Spawns [WORKER_COUNT] real OS processes (verified by distinct PIDs) via `posix_spawn`, each
 * running the `compressWorker` K/N executable (see `iosMain/.../worker/CompressWorkerMain.kt`)
 * with [COROUTINES_PER_WORKER] parallel coroutines. All [WORKER_COUNT] × [COROUTINES_PER_WORKER]
 * tasks go through `createKompressor().image.compress(...)` on distinct output paths.
 *
 * The point is to catch iOS-specific regressions that intra-process coverage cannot — e.g. a
 * static `NSLock`/`dispatch_semaphore_t` added around `AVAssetWriter` or `UIGraphicsBeginImage
 * ContextWithOptions` in `iosMain`. An intra-process 16-coroutine stress test would not
 * serialise on such a lock because all coroutines share the same dispatcher pool; a true
 * cross-process grid forces each worker to hold its own instance and trip a process-wide lock
 * if one exists. See the Android mirror at
 * `androidHostTest/ConcurrentCompressInterProcessTest` for the corresponding coverage on the
 * JVM side (no cross-platform KDoc link — the sibling is not in iosTest's resolver scope).
 *
 * **Scope.** Simulator-only. On-device inter-process coverage is out of scope — the compiled
 * `iosMain` code is identical between simulator and device, so a static-lock regression would
 * show up here first; device-specific hardware contention is tracked separately under the
 * device CI budget.
 *
 * **Discovery.** The worker binary's absolute path is injected by Gradle via the
 * `KOMPRESSOR_COMPRESS_WORKER_PATH` env var (see `kompressor/build.gradle.kts`). The env var
 * is propagated through `xcrun simctl spawn` to the simulator process and read here via
 * [platform.posix.getenv]. If the env var is missing, the test is skipped rather than failing
 * — lets someone run `:iosSimulatorArm64Test` directly without going through the Gradle task
 * wiring (e.g. from an IDE) without a confusing red herring.
 */
class ConcurrentCompressInterProcessTest {

    private lateinit var tempDir: String
    private lateinit var workerPath: String

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "kompressor-interproc-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        workerPath = readWorkerPath() ?: ""
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
    }

    @Test
    fun fourProcessesTimesFourCoroutines_allSucceed() {
        if (workerPath.isEmpty()) {
            // See class KDoc: env var absent means the test was invoked outside the Gradle
            // `iosSimulatorArm64Test` task that wires the worker binary path. Skip cleanly —
            // failing here would punish anyone running a single test from the IDE.
            println(
                "[inter-process] KOMPRESSOR_COMPRESS_WORKER_PATH not set, skipping. " +
                    "Run via `./gradlew :kompressor:iosSimulatorArm64Test` to exercise this test.",
            )
            return
        }
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(workerPath),
            "compressWorker binary not found at $workerPath — did `linkCompressWorkerDebug" +
                "ExecutableIosSimulatorArm64` run?",
        )

        // One shared input PNG for all 16 compressions. Using a 4x4 palette-indexed fixture
        // (tiny, a few hundred bytes) keeps the per-worker wall time low so the MAX_WALL_TIME
        // ceiling bites only when workers are actually serialising, not because the image is
        // heavy.
        val inputPath = tempDir + "input.png"
        writeBytes(inputPath, MinimalPngFixtures.indexed4x4())

        val startMark = TimeSource.Monotonic.markNow()
        val pids = mutableListOf<Int>()
        val outputDirs = mutableListOf<String>()
        for (workerIndex in 0 until WORKER_COUNT) {
            val workerOutDir = tempDir + "worker_$workerIndex"
            NSFileManager.defaultManager.createDirectoryAtPath(
                workerOutDir, withIntermediateDirectories = true, attributes = null, error = null,
            )
            outputDirs += workerOutDir
            pids += spawnWorker(
                binary = workerPath,
                args = listOf(
                    workerPath,
                    inputPath,
                    workerOutDir,
                    COROUTINES_PER_WORKER.toString(),
                    QUALITY.toString(),
                ),
            )
        }

        // Distinct-PID assertion is the ticket's explicit success criterion for "≥ 2 real OS
        // processes". A bug where posix_spawn failed silently and we re-used a single pid
        // would make the rest of the test look like it passed — this check catches that.
        assertEquals(WORKER_COUNT, pids.toSet().size, "Expected $WORKER_COUNT distinct PIDs, got $pids")

        pids.forEachIndexed { i, pid ->
            val exitCode = waitForPid(pid)
            assertEquals(
                expected = 0, actual = exitCode,
                message = "Worker $i (pid=$pid) exited $exitCode — suspected compress() failure " +
                    "or cross-process serialisation regression",
            )
        }

        val elapsedSeconds = startMark.elapsedNow().inWholeMilliseconds / MILLIS_PER_SEC
        assertTrue(
            elapsedSeconds < MAX_WALL_TIME_SECONDS,
            "Inter-process grid took ${elapsedSeconds}s (>= ${MAX_WALL_TIME_SECONDS}s) — " +
                "workers may be serialising on a process-wide lock",
        )

        for (workerIndex in 0 until WORKER_COUNT) {
            val dir = outputDirs[workerIndex]
            for (coroutineIndex in 0 until COROUTINES_PER_WORKER) {
                val outPath = "$dir/out_$coroutineIndex.jpg"
                assertTrue(
                    NSFileManager.defaultManager.fileExistsAtPath(outPath),
                    "Missing output: $outPath",
                )
                assertTrue(fileSize(outPath) > 0, "Empty output: $outPath")
            }
        }
    }

    /**
     * Wraps [kmp_posix_spawn] with argv allocation done inside [memScoped]. Every allocated
     * C string is freed as soon as the call returns — the spawned child inherits a copy of
     * the arg list via the kernel's exec path, so the parent doesn't need to keep those
     * buffers alive.
     *
     * Returns the child pid as an [Int]. `pid_t` on Darwin is `int32_t`, so the round-trip
     * through Int is lossless; exposing Int across the call boundary keeps the call site
     * readable.
     */
    private fun spawnWorker(binary: String, args: List<String>): Int = memScoped {
        val argv: CPointer<CPointerVar<ByteVar>> = allocArray(args.size + 1)
        args.forEachIndexed { i, arg ->
            argv[i] = arg.cstr.getPointer(this)
        }
        argv[args.size] = null

        // envp = null → inherit parent environment, which carries the iOS simulator bootstrap
        // plus the DYLD_* overrides simctl injects so the child can find the iOS frameworks.
        // Passing a fresh `envp` here would strand the child outside the simulator's bootstrap
        // namespace and UIKit/AVFoundation would fail to load. File descriptors are inherited
        // (no file_actions plumbed through the shim), so the child's `println(...)` lands in
        // this process's stdout and surfaces in the Kotlin test report.
        val pid = kmp_posix_spawn(binary, argv, null)
        check(pid > 0) { "kmp_posix_spawn failed for $binary: rc=$pid" }
        pid
    }

    /**
     * Blocks on [kmp_waitpid_exit]. Returns the child's exit code (0..255) or -1 if the
     * child was killed by a signal. The shim unpacks `WIFEXITED`/`WEXITSTATUS` on the C
     * side so K/N code doesn't have to reimplement the status-word bit layout.
     */
    private fun waitForPid(pid: Int): Int = kmp_waitpid_exit(pid)

    private fun readWorkerPath(): String? {
        val raw = getenv(WORKER_ENV_VAR) ?: return null
        return raw.toKString().ifEmpty { null }
    }

    private companion object {
        const val WORKER_COUNT = 4
        const val COROUTINES_PER_WORKER = 4
        const val QUALITY = 80

        // Loose upper bound: 16 image compressions on a warm M1 finish in well under 10s, but
        // cold simulator boot + K/N startup + Xcode caches can add 20-30s on a fresh CI runner.
        // A regression that serialises the grid on a process-wide lock would push far past
        // this; a flaky cold start will not. Matches the Android sibling's budget.
        const val MAX_WALL_TIME_SECONDS = 90.0
        const val MILLIS_PER_SEC = 1000.0

        const val WORKER_ENV_VAR = "KOMPRESSOR_COMPRESS_WORKER_PATH"
    }
}
