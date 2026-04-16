/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Process-level concurrency regression guard for CRA-14.
 *
 * Launches [WORKER_COUNT] JVM subprocesses in parallel, each running
 * [co.crackn.kompressor.testutil.InterProcessCompressWorker]`.main` with
 * [COROUTINES_PER_WORKER] coroutines. All [WORKER_COUNT] × [COROUTINES_PER_WORKER] tasks share
 * Kompressor's shared commonMain code path (config validation + real file I/O on distinct
 * paths) but run in genuinely separate OS processes.
 *
 * If someone ever introduces a process-wide lock in shared code — a static [java.nio.channels
 * .FileLock] on a constant path, a named semaphore, a rendezvous file — the workers will
 * serialise and the wall-time assertion at [MAX_WALL_TIME_SECONDS] will fire, or the
 * [WAIT_TIMEOUT_SECONDS] wait-for timeout will cut off a true deadlock. The intra-process
 * [ConcurrentCompressionTest] sibling on device/iOS cannot catch that class of regression.
 *
 * Uses host JVM only (does not need an Android device): the worker deliberately avoids
 * `android.graphics.*` — which throws "Stub!" on the host — and exercises the shared
 * [co.crackn.kompressor.image.ImageCompressionConfig] init path that every real `compress()`
 * call runs through. See `docs/threading-model.md` for the full rationale.
 */
class ConcurrentCompressInterProcessTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory(prefix = "kompressor-interproc-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun fourProcessesTimesFourCoroutines_allSucceed() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        classpath shouldNotBe null

        val startNanos = System.nanoTime()
        val processes = (0 until WORKER_COUNT).map { workerIndex ->
            val workerOutDir = File(tempDir, "worker_$workerIndex").apply { mkdirs() }
            val command = listOf(
                javaBin,
                "-cp", classpath,
                WORKER_MAIN_CLASS,
                workerIndex.toString(),
                workerOutDir.absolutePath,
                COROUTINES_PER_WORKER.toString(),
                PAYLOAD_BYTES.toString(),
            )
            ProcessBuilder(command)
                .redirectOutput(File(tempDir, "worker_${workerIndex}_stdout.log"))
                .redirectError(File(tempDir, "worker_${workerIndex}_stderr.log"))
                .start()
        }

        processes.forEachIndexed { i, p ->
            val exited = p.waitFor(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                p.destroyForcibly()
                fail("Worker $i did not exit within ${WAIT_TIMEOUT_SECONDS}s — suspected deadlock")
            }
            val exitValue = p.exitValue()
            if (exitValue != 0) {
                val stderr = File(tempDir, "worker_${i}_stderr.log").readText()
                fail("Worker $i exited $exitValue. stderr:\n$stderr")
            }
        }

        val elapsedSeconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND
        if (elapsedSeconds >= MAX_WALL_TIME_SECONDS) {
            fail(
                "Inter-process grid took ${elapsedSeconds}s (>= ${MAX_WALL_TIME_SECONDS}s) — " +
                    "workers may be serialising on a process-wide lock",
            )
        }

        for (workerIndex in 0 until WORKER_COUNT) {
            val workerOutDir = File(tempDir, "worker_$workerIndex")
            for (coroutineIndex in 0 until COROUTINES_PER_WORKER) {
                val outFile = File(
                    workerOutDir,
                    "worker${workerIndex}_coro${coroutineIndex}_out.bin",
                )
                outFile.exists() shouldBe true
                outFile.length() shouldBe PAYLOAD_BYTES.toLong()
            }
        }
    }

    private companion object {
        const val WORKER_COUNT = 4
        const val COROUTINES_PER_WORKER = 4
        const val PAYLOAD_BYTES = 65_536
        const val WAIT_TIMEOUT_SECONDS = 60L

        // Loose upper bound: 16 short file-copy tasks across 4 JVMs should finish in seconds on
        // a warm runner, but cold GitHub-hosted runners can spend 10-20s just on JVM startup ×
        // N. A regression that serialises the grid on a process-wide lock would push well past
        // this; a flaky cold start will not.
        const val MAX_WALL_TIME_SECONDS = 90L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val WORKER_MAIN_CLASS =
            "co.crackn.kompressor.testutil.InterProcessCompressWorkerKt"
    }
}
