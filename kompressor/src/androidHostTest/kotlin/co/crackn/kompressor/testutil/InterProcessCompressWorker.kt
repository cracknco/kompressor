/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import co.crackn.kompressor.image.ImageCompressionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Subprocess entry point for [ConcurrentCompressInterProcessTest][
 *   co.crackn.kompressor.ConcurrentCompressInterProcessTest].
 *
 * Runs in its own JVM, launched via `ProcessBuilder` from the host test. Each worker runs
 * `coroutineCount` coroutines in parallel; the test launches multiple workers and asserts all
 * outputs are valid — catching any JVM-wide or filesystem-level lock that Kompressor might
 * accidentally introduce in shared commonMain code.
 *
 * Deliberately does **not** call `AndroidImageCompressor.compress()`: `android.graphics.Bitmap`
 * et al. throw "Stub!" on the host JVM. Instead, each coroutine exercises the shared commonMain
 * config-validation path (which every real `compress()` goes through) and real file I/O on a
 * path unique to this worker × coroutine pair. That is sufficient to detect a process-wide
 * lock regression — the stated goal of the test — without needing a real codec stack.
 */
internal object InterProcessCompressWorker

fun main(args: Array<String>) {
    if (args.size != ARG_COUNT) {
        System.err.println(
            "InterProcessCompressWorker usage: <workerIndex> <outputDir> <coroutineCount> <inputBytes>",
        )
        exitProcess(EXIT_BAD_ARGS)
    }
    val workerIndex = args[0].toInt()
    val outputDir = File(args[1]).apply { mkdirs() }
    val coroutineCount = args[2].toInt()
    val inputBytes = args[3].toInt()

    try {
        runBlocking {
            coroutineScope {
                (0 until coroutineCount).map { coroutineIndex ->
                    async(Dispatchers.Default) {
                        runOne(workerIndex, coroutineIndex, outputDir, inputBytes)
                    }
                }.awaitAll()
            }
        }
        exitProcess(0)
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
        System.err.println("worker=$workerIndex failed: ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(EXIT_RUNTIME_FAILURE)
    }
}

private fun runOne(workerIndex: Int, coroutineIndex: Int, outputDir: File, inputBytes: Int) {
    val inputFile = File(outputDir, "worker${workerIndex}_coro${coroutineIndex}_in.bin")
    val outputFile = File(outputDir, "worker${workerIndex}_coro${coroutineIndex}_out.bin")

    // Deterministic pseudo-random payload keyed on worker+coroutine so a collision on either axis
    // would be visible as byte-level corruption, not just a missing file.
    val seed = (workerIndex.toLong() shl SEED_WORKER_SHIFT) or coroutineIndex.toLong()
    val payload = ByteArray(inputBytes)
    var rng = seed xor PRNG_XOR_CONSTANT
    for (i in payload.indices) {
        rng = rng * PRNG_MUL + PRNG_ADD
        payload[i] = (rng ushr PRNG_HIGH_BITS_SHIFT).toByte()
    }
    inputFile.writeBytes(payload)

    // commonMain code path — same ImageCompressionConfig init that every real compress() call
    // runs through. If anyone ever moves this constructor behind a static lock, this subprocess
    // would serialise on it and the 4×4 grid test would take ~4× longer than expected.
    ImageCompressionConfig(quality = QUALITY, maxWidth = MAX_DIM, maxHeight = MAX_DIM)

    outputFile.writeBytes(inputFile.readBytes())

    println("worker=$workerIndex coro=$coroutineIndex ok=true size=${outputFile.length()}")
}

private const val ARG_COUNT = 4
private const val EXIT_BAD_ARGS = 2
private const val EXIT_RUNTIME_FAILURE = 1

private const val QUALITY = 80
private const val MAX_DIM = 1920

private const val SEED_WORKER_SHIFT = 16
private const val PRNG_XOR_CONSTANT = 0x5DEECE66DL
private const val PRNG_MUL = 6364136223846793005L
private const val PRNG_ADD = 1442695040888963407L
private const val PRNG_HIGH_BITS_SHIFT = 56
