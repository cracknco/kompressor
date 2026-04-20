/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

/**
 * Severity levels emitted by the library through [KompressorLogger].
 *
 * Ordering matches the conventional Android / OSLog hierarchy:
 * [VERBOSE] &lt; [DEBUG] &lt; [INFO] &lt; [WARN] &lt; [ERROR]. Implementations that filter by
 * level should do so with [LogLevel.priority] comparisons so the ordering stays authoritative.
 */
public enum class LogLevel(
    /** Numeric priority. Higher is more severe; use for threshold filtering. */
    public val priority: Int,
) {
    /** Fine-grained per-step trace. Silent by default; enable when debugging a specific call site. */
    VERBOSE(priority = 1),

    /** Developer-oriented detail about decisions made (encoder choice, fallback paths). */
    DEBUG(priority = 2),

    /** High-level operation lifecycle (compress started, compress completed). */
    INFO(priority = 3),

    /** Recoverable anomaly (HW→SW fallback, tone-mapping applied, passthrough disabled). */
    WARN(priority = 4),

    /** Unrecoverable failure — the operation is about to surface a typed error to the caller. */
    ERROR(priority = 5),
    ;
}

/**
 * Pluggable logging sink for Kompressor.
 *
 * The library never writes to a log channel directly. Every diagnostic message is emitted through
 * an instance of this interface, letting the consumer route logs to Timber, Logcat, `os_log`,
 * SwiftLog, CocoaLumberjack, Sentry, Datadog, or any custom pipeline.
 *
 * ## Contract
 *
 * Implementations **must** satisfy the following guarantees:
 *
 * ### Thread-safety
 * [log] may be invoked from any thread, including multiple threads concurrently. Implementations
 * are responsible for any synchronisation their backend requires. The default [PlatformLogger]
 * delegates to `android.util.Log` and `NSLog` — both of which are thread-safe — so consumers
 * wrapping their own backend (e.g. a file-based logger) must not assume single-threaded access.
 *
 * ### No-throw
 * [log] **must not** throw. A logger whose implementation throws (disk full, network error,
 * serialisation failure) would otherwise crash a compression pipeline mid-transcode. The library
 * wraps every call site in a catch-all before emitting, but implementations should still avoid
 * throwing — thrown exceptions are swallowed and the original log line is dropped on the floor.
 *
 * ### Performance
 * [log] is called on hot paths (progress updates, per-step decisions at `VERBOSE` / `DEBUG`).
 * Implementations **must** filter by [LogLevel] as early as possible — ideally before performing
 * any string concatenation, formatting, or allocation. Consumers that disable [LogLevel.DEBUG] at
 * production time should pay zero cost beyond an enum comparison.
 *
 * ## Default
 *
 * Calling [co.crackn.kompressor.createKompressor] without a `logger` argument installs
 * [PlatformLogger] — `android.util.Log` on Android and `NSLog`-backed unified logging on iOS.
 * [LogLevel.WARN] and [LogLevel.ERROR] are visible out-of-the-box; [LogLevel.DEBUG] and
 * [LogLevel.VERBOSE] are silent in release builds.
 *
 * For opt-in silence (recommended in production when log volume is a cost), pass [NoOpLogger]:
 * `createKompressor(logger = NoOpLogger)`.
 *
 * ## Custom logger example
 * ```kotlin
 * class TimberLogger : KompressorLogger {
 *     override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
 *         val priority = when (level) {
 *             LogLevel.VERBOSE -> Log.VERBOSE
 *             LogLevel.DEBUG -> Log.DEBUG
 *             LogLevel.INFO -> Log.INFO
 *             LogLevel.WARN -> Log.WARN
 *             LogLevel.ERROR -> Log.ERROR
 *         }
 *         Timber.tag(tag).log(priority, throwable, message)
 *     }
 * }
 *
 * val kompressor = createKompressor(logger = TimberLogger())
 * ```
 *
 * See `docs/logging.md` for Timber / SwiftLog / CocoaLumberjack integration recipes and
 * `docs/adr/003-logger-contract.md` for the rules the library itself follows when choosing a
 * level and tag.
 */
public fun interface KompressorLogger {
    /**
     * Emits a diagnostic log record.
     *
     * @param level severity of the record — implementations should filter here.
     * @param tag short category identifier. Library-emitted tags are prefixed with `Kompressor.*`
     *   and are kept under 23 characters so they remain usable as-is on Android API &lt; 26,
     *   where [android.util.Log] enforces that limit.
     * @param message the log body. May be empty (callers should avoid this, but implementations
     *   must still not throw on empty input).
     * @param throwable optional cause attached to this record. `null` on the success / info path;
     *   non-null on [LogLevel.ERROR] and some [LogLevel.WARN] emissions. Implementations should
     *   render the full stack trace for `ERROR` and the type / message for `WARN`.
     *
     * **Must not throw.** See the class KDoc for the full contract.
     */
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)

    /**
     * Optional fast-path hook: return `false` to tell the library this logger will ignore records
     * at [level], so the library can skip building the message string and calling [log].
     *
     * The default returns `true`, keeping the ADR-003 § 3 rule — "library does not filter,
     * implementation does" — intact for simple loggers that always dispatch. Implementations that
     * statically silence a level (e.g. [NoOpLogger]) or use a threshold known up front should
     * override this to avoid per-emission formatting cost on hot paths like per-sample-buffer
     * `VERBOSE` traces.
     *
     * **Must not throw.** The library wraps calls in a catch-all; a thrown exception is swallowed
     * and the caller proceeds as if `isEnabled` returned `true` (safer default — erring on the
     * side of dispatching keeps diagnostics visible rather than silently dropped).
     */
    public fun isEnabled(level: LogLevel): Boolean = true
}
