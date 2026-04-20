/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

/**
 * Internal no-throw facade over a user-supplied [KompressorLogger].
 *
 * The public contract already tells consumers that their [KompressorLogger.log] implementations
 * *must not* throw — but the library can't rely on that. A single misbehaving logger (disk full,
 * serialisation bug, reflection error) would otherwise crash a compression pipeline mid-way
 * through a transcode and lose user data.
 *
 * Every library-side emission goes through [SafeLogger]; exceptions thrown by the underlying
 * logger are caught and dropped on the floor. We never re-emit a swallowed exception via the
 * same logger (that would recurse and compound the damage on a logger that's already broken).
 *
 * Lazy message producers (e.g. [debug] / [verbose]) accept a `() -> String` lambda so the call
 * site stays readable without forcing allocation at the argument position. [SafeLogger] queries
 * [KompressorLogger.isEnabled] once per emission and skips the lambda entirely when the delegate
 * reports the level as disabled — this makes `NoOpLogger` truly allocation-free on hot paths and
 * lets threshold-based loggers (production WARN-only, release DEBUG-off) short-circuit before
 * any string concatenation runs. The default `isEnabled` returns `true`, preserving the
 * ADR-003 § 3 baseline ("library does not filter, implementation does") for loggers that don't
 * implement the hook. At ERROR / WARN we still materialise eagerly at the call site because the
 * allocation is negligible next to the failing I/O those levels describe, and skipping them would
 * hide real failures.
 */
internal class SafeLogger(private val delegate: KompressorLogger) {
    // Thread-safety: `delegate` is immutable after construction. Every call reads the same
    // reference, so no synchronisation is required around dispatch; synchronisation inside the
    // delegate is the delegate's own concern (documented on KompressorLogger).

    fun verbose(tag: String, throwable: Throwable? = null, message: () -> String) {
        emitLazy(LogLevel.VERBOSE, tag, throwable, message)
    }

    fun debug(tag: String, throwable: Throwable? = null, message: () -> String) {
        emitLazy(LogLevel.DEBUG, tag, throwable, message)
    }

    fun info(tag: String, throwable: Throwable? = null, message: () -> String) {
        emitLazy(LogLevel.INFO, tag, throwable, message)
    }

    fun warn(tag: String, throwable: Throwable? = null, message: () -> String) {
        emitEager(LogLevel.WARN, tag, message(), throwable)
    }

    fun error(tag: String, throwable: Throwable? = null, message: () -> String) {
        emitEager(LogLevel.ERROR, tag, message(), throwable)
    }

    /**
     * Emits [level] with a lazily-built message, short-circuiting when the delegate reports the
     * level as disabled.
     *
     * The `isEnabled` default is `true` for delegates that don't implement the hook, so
     * behaviour is unchanged for them (message built + dispatched). Delegates that override —
     * notably [NoOpLogger], and threshold-based consumer loggers — skip the lambda entirely,
     * avoiding string-concat cost on hot paths. A thrown `isEnabled` is caught and treated as
     * `true`: we would rather pay the cost than silently drop a diagnostic on behalf of a
     * broken predicate.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun emitLazy(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        val enabled = try {
            delegate.isEnabled(level)
        } catch (_: Throwable) {
            true
        }
        if (!enabled) return
        emitEager(level, tag, message(), throwable)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun emitEager(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        try {
            delegate.log(level, tag, message, throwable)
        } catch (_: Throwable) {
            // Swallow deliberately. See class KDoc — re-throwing here would crash the
            // compression pipeline on behalf of a broken logger, and re-logging would
            // recurse into the same broken sink.
        }
    }
}

/**
 * Logs the outcome of [block] through [logger] under [tag].
 *
 * Writes an INFO lifecycle line before [block] runs (via [startMessage]), an INFO line on
 * successful completion (via [successMessage]), and an ERROR line on failure. Failures re-throw
 * — this helper only adds observability, it does not swallow. Cancellation is forwarded without
 * an error log (a cancelled coroutine is not a library failure and logging it as ERROR would
 * pollute app metrics).
 *
 * Used by each compressor's `compress()` entry point so the lifecycle shape is identical across
 * image / video / audio on both platforms.
 */
// Not `inline`: `startMessage` / `failureMessage` are stored as arguments to `info` / `error`
// (so would need `noinline`) and `successMessage` is invoked inside a child lambda (so would
// need `crossinline`). At that point every parameter would need an annotation and the call-site
// pays the lambda-allocation cost anyway — inlining is not worth the ceremony. The wrapper is
// called once per compress(), so the allocation is negligible next to the transcode it guards.
internal suspend fun <T> SafeLogger.instrumentCompress(
    tag: String,
    startMessage: () -> String,
    successMessage: (T) -> String,
    failureMessage: () -> String,
    block: suspend () -> T,
): T {
    info(tag, message = startMessage)
    return try {
        val result = block()
        info(tag) { successMessage(result) }
        result
    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
        // Re-throw without logging as an error — cancellation is a normal cooperative signal,
        // not a library failure. Debug-level only so a consumer tracing flow can still see it.
        debug(tag) { "$tag cancelled" }
        throw ce
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
        error(tag, t, failureMessage)
        throw t
    }
}
