/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

/**
 * A [KompressorLogger] that discards every record.
 *
 * Opt in when the consumer wants a strictly silent library — typically in production builds where
 * log volume is a measurable cost (Google Play Pre-Launch, CI transcode farms, background workers).
 *
 * ```kotlin
 * val kompressor = createKompressor(logger = NoOpLogger)
 * ```
 *
 * Not the library default — `createKompressor()` installs [PlatformLogger] so integrators see
 * WARN / ERROR out-of-the-box during initial wiring. Switch to [NoOpLogger] deliberately once
 * you've confirmed no silent failures.
 */
public object NoOpLogger : KompressorLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // Intentionally empty — this logger discards every record.
    }

    // Short-circuit the library's lazy-message builder on every call. Without this, a consumer
    // wiring `createKompressor(logger = NoOpLogger)` would still pay the string-concat cost on
    // every `logger.debug { ... }` because the library materialises the message before dispatch.
    // Returning `false` for all levels lets SafeLogger skip the message lambda entirely.
    override fun isEnabled(level: LogLevel): Boolean = false
}
