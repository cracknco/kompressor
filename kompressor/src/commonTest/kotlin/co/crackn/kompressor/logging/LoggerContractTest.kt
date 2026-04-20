/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Contract coverage for [KompressorLogger]: every library-supplied logger (and the library's
 * internal [SafeLogger] facade around a custom one) must satisfy the documented guarantees —
 * no-throw, thread-safe, level-aware. These tests are platform-agnostic so both JVM host tests
 * and K/N simulator tests enforce the contract.
 */
class LoggerContractTest {

    // ── Thread-safety / no-throw ────────────────────────────

    @Test
    fun safeLogger_swallowsExceptionsThrownByDelegate() {
        val throwing = KompressorLogger { _, _, _, _ -> error("delegate is broken") }
        val safe = SafeLogger(throwing)

        // None of these five emissions must propagate the delegate's exception. That's the
        // whole point of the SafeLogger facade: a broken user logger must not crash the
        // compression pipeline that called into it.
        safe.verbose("tag") { "v" }
        safe.debug("tag") { "d" }
        safe.info("tag") { "i" }
        safe.warn("tag") { "w" }
        safe.error("tag") { "e" }
    }

    @Test
    fun customLogger_receivesEveryLevelAndTagPair() {
        val recorder = RecordingLogger()
        val safe = SafeLogger(recorder)

        safe.verbose("tag.v") { "v" }
        safe.debug("tag.d") { "d" }
        safe.info("tag.i") { "i" }
        safe.warn("tag.w") { "w" }
        val failure = IllegalStateException("boom")
        safe.error("tag.e", failure) { "e" }

        recorder.records.size shouldBe 5
        recorder.records.map { it.level } shouldBe listOf(
            LogLevel.VERBOSE,
            LogLevel.DEBUG,
            LogLevel.INFO,
            LogLevel.WARN,
            LogLevel.ERROR,
        )
        recorder.records.last().throwable shouldBe failure
    }

    @Test
    fun concurrentEmissions_deliverEveryRecord() = runBlocking(Dispatchers.Default) {
        // Thread-safety contract: a logger invoked from N coroutines on a multi-thread dispatcher
        // must observe every record. Each coroutine uses a distinct tag ("tag-$workerId") and the
        // recorder partitions by tag, so no cross-worker races can drop records — the assertion
        // measures the contract's reach rather than fighting a shared counter.
        //
        // Synchronisation model: each bucket's `MutableList` is written by exactly one coroutine
        // during its lifetime, so no data race between writers. The main-thread reads in the final
        // `repeat(...) { recorder.countFor(...) }` block happen AFTER `jobs.awaitAll()`, which
        // establishes a happens-before edge — kotlinx.coroutines' Deferred.await, like Job.join,
        // publishes the awaited coroutine's writes to the awaiter. So the unsynchronised lists
        // are safe to read here. A shared atomic counter was the obvious alternative but would
        // have pulled in `kotlinx-atomicfu` as a test-only dependency — the partition-by-tag +
        // awaitAll design keeps the test at zero extra deps.
        val recorder = PartitionedRecordingLogger()
        val safe = SafeLogger(recorder)

        val coroutineCount = CONCURRENT_COROUTINES
        val perCoroutine = EMISSIONS_PER_COROUTINE
        val jobs = (0 until coroutineCount).map { workerId ->
            async {
                repeat(perCoroutine) { i ->
                    safe.info("tag-$workerId") { "msg-$workerId-$i" }
                }
            }
        }
        jobs.awaitAll()

        // Every worker's partition must contain exactly `perCoroutine` records, proving no
        // emissions were lost to concurrency. `awaitAll()` above is the happens-before edge
        // that makes these reads see every per-bucket write.
        repeat(coroutineCount) { workerId ->
            recorder.countFor("tag-$workerId") shouldBe perCoroutine
        }
    }

    // ── Level filtering contract ────────────────────────────

    @Test
    fun logLevelPriorities_areStrictlyOrdered() {
        // Level ordering is part of the public contract — custom loggers use the priority field
        // to filter (e.g. "ship DEBUG+ to Logcat, WARN+ to Sentry"). Reverse this order by
        // accident and every downstream filter silently inverts.
        val priorities = LogLevel.entries.map { it.priority }
        priorities shouldBe priorities.sorted()
        LogLevel.VERBOSE.priority shouldBe 1
        LogLevel.ERROR.priority shouldBe 5
    }

    // ── Injection into createKompressor — deferred to platform tests ──
    // The `createKompressor(logger = ...)` factory is expect/actual so its full exercise belongs
    // in `androidHostTest` / `iosTest`. `LoggerInjectionTest` there verifies the injected logger
    // actually receives records during a compress() call.

    // ── Private helpers ─────────────────────────────────────

    private data class Record(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private class RecordingLogger : KompressorLogger {
        // Unsynchronised — only used from the single-threaded tests above.
        val records = mutableListOf<Record>()

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            records += Record(level, tag, message, throwable)
        }
    }

    /**
     * Partitioned recorder used for the concurrent emission test. Each expected tag maps to its
     * own pre-allocated list; because every coroutine writes to a distinct tag, no two coroutines
     * ever touch the same list, so we need no synchronisation to count emissions reliably. A
     * shared atomic counter was the obvious alternative but would have required `kotlinx-atomicfu`
     * as a test-only dependency — the partition-by-tag design keeps the test at zero extra deps.
     */
    private class PartitionedRecordingLogger : KompressorLogger {
        private val buckets: Map<String, MutableList<String>> =
            (0 until CONCURRENT_COROUTINES).associate { "tag-$it" to mutableListOf() }

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            // Bucket is owned by exactly one coroutine for the duration of the test.
            buckets[tag]?.add(message)
        }

        fun countFor(tag: String): Int = buckets[tag]?.size ?: 0
    }

    private companion object {
        const val CONCURRENT_COROUTINES = 16
        const val EMISSIONS_PER_COROUTINE = 100
    }
}
