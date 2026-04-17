/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor.worker

import co.crackn.kompressor.createKompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import kotlin.system.exitProcess

/**
 * Entry point for the `compressWorker` executable binary that backs
 * [co.crackn.kompressor.ConcurrentCompressInterProcessTest][ConcurrentCompressInterProcessTest] on
 * iOS simulator.
 *
 * Linked into a dedicated `compressWorker` K/N executable on the `iosSimulatorArm64` target
 * (see `kompressor/build.gradle.kts`) and invoked via `posix_spawn` from the test host. This
 * gives us real cross-process coverage of `createKompressor().image.compress(...)` — the
 * missing piece from CRA-14 that was tracked in `docs/threading-model.md#ios-inter-process-coverage--known-gap`.
 *
 * ### CLI contract
 *
 * ```
 * compressWorker <inputPath> <outputDir> <coroutineCount> <quality>
 * ```
 *
 * - `inputPath`: absolute path to a source image readable by `UIImage(contentsOfFile:)`.
 * - `outputDir`: absolute path to an existing directory. Each coroutine writes
 *   `out_<i>.jpg` there.
 * - `coroutineCount`: number of parallel `compress()` coroutines to run inside this
 *   process (matches Android's `COROUTINES_PER_WORKER`).
 * - `quality`: 0–100, forwarded to [ImageCompressionConfig.quality].
 *
 * Exits `0` on full success, `EXIT_BAD_ARGS` (2) on CLI validation failure, or
 * `EXIT_RUNTIME_FAILURE` (1) with a stderr message on any compression failure. A non-zero
 * exit value is what the parent test asserts against to detect a regression — the stderr
 * payload is captured for diagnostics.
 *
 * Kept deliberately dependency-free on test fixtures (no `MinimalPngFixtures` reference): the
 * host test writes the source image once and passes its path in, keeping the worker binary
 * small and its responsibilities narrow.
 *
 * Naming note: lives in its own `worker` sub-package so the top-level `main` symbol doesn't
 * collide with any other entry point that might be added later, and so the function is
 * trivially filterable from public-API reviews.
 */
@Suppress("TooGenericExceptionCaught")
fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    try {
        runBlocking { runCompressionGrid(parsed) }
        exitProcess(0)
    } catch (t: Throwable) {
        logErr("compressWorker runtime failure: ${t.message}")
        exitProcess(EXIT_RUNTIME_FAILURE)
    }
}

/**
 * Parsed, validated worker CLI arguments. Private because the executable binary is the sole
 * surface for this module — exposing a record type across iosMain would pollute the public API
 * for something that only the worker's `main` consumes.
 */
private data class WorkerArgs(
    val inputPath: String,
    val outputDir: String,
    val coroutineCount: Int,
    val quality: Int,
)

/**
 * Parses argv with fail-fast exits on any validation error. Split out of [main] to satisfy
 * detekt's `LongMethod` rule and to keep the happy path in `main` readable — the parser is the
 * one place where `exitProcess(EXIT_BAD_ARGS)` fires, so grep-for-exit-code lands here directly.
 *
 * Side effect: ensures `outputDir` exists (creates intermediates) so the spawning test does not
 * need to mkdir per-worker beyond the top-level worker_N directory.
 */
private fun parseArgs(args: Array<String>): WorkerArgs {
    if (args.size != ARG_COUNT) {
        logErr(
            "compressWorker usage: <inputPath> <outputDir> <coroutineCount> <quality>; got " +
                "args=${args.size}",
        )
        exitProcess(EXIT_BAD_ARGS)
    }
    val coroutineCount = args[2].toIntOrNull() ?: run {
        logErr("coroutineCount must be an int, got '${args[2]}'")
        exitProcess(EXIT_BAD_ARGS)
    }
    val quality = args[3].toIntOrNull() ?: run {
        logErr("quality must be an int, got '${args[3]}'")
        exitProcess(EXIT_BAD_ARGS)
    }
    if (!NSFileManager.defaultManager.fileExistsAtPath(args[0])) {
        logErr("inputPath does not exist: ${args[0]}")
        exitProcess(EXIT_BAD_ARGS)
    }
    NSFileManager.defaultManager.createDirectoryAtPath(
        args[1], withIntermediateDirectories = true, attributes = null, error = null,
    )
    return WorkerArgs(args[0], args[1], coroutineCount, quality)
}

/**
 * Fan-out/fan-in the N parallel `compress()` calls. Suspend (not blocking) so callers pick the
 * dispatcher — [main] uses [runBlocking] with the default context; a future unit test could run
 * this under a `runTest` dispatcher without modification.
 */
private suspend fun runCompressionGrid(args: WorkerArgs) {
    val kompressor = createKompressor()
    val config = ImageCompressionConfig(quality = args.quality)
    coroutineScope {
        (0 until args.coroutineCount).map { i ->
            async(Dispatchers.Default) {
                val outputPath = "${args.outputDir}/out_$i.jpg"
                val result = kompressor.image.compress(args.inputPath, outputPath, config)
                if (result.isFailure) {
                    throw IllegalStateException(
                        "coroutine $i failed: ${result.exceptionOrNull()?.message}",
                        result.exceptionOrNull(),
                    )
                }
            }
        }.awaitAll()
    }
}

private fun logErr(message: String) {
    // K/N on iOS routes `println` to stdout, and exposing `platform.posix.stderr` reliably
    // across SDK versions is more pain than it's worth for a test helper. The parent
    // inherits fd 1 by default (our `kmp_posix_spawn` shim passes NULL for `file_actions`),
    // so this marker surfaces in the Kotlin test report without any plumbing on the host
    // side. The `[compressWorker-ERR]` prefix is the handle a future reader greps for.
    println("[compressWorker-ERR] $message")
}

private const val ARG_COUNT = 4
private const val EXIT_BAD_ARGS = 2
private const val EXIT_RUNTIME_FAILURE = 1
