/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

import platform.Foundation.NSLog

/**
 * iOS [PlatformLogger] actual — delegates to `NSLog`, which routes to Apple's unified
 * logging system (the `os_log` infrastructure behind Console.app / `log stream` / `OSLogStore`).
 *
 * `os_log` itself is a C preprocessor macro in `<os/log.h>`; Kotlin/Native can only call
 * exported C functions, not macros, so the pragmatic default is [NSLog]. On modern iOS
 * `NSLog` funnels straight into `_os_log_impl`, so the user-visible behaviour (Console.app,
 * device syslog, `OSLogStore`) is identical to what a direct `os_log` emit would produce.
 *
 * Level is rendered as a short prefix (`[V]`, `[D]`, `[I]`, `[W]`, `[E]`) so filtered views in
 * Console.app can still distinguish records — the unified logging ingestion path NSLog uses does
 * not preserve `os_log_type_t` on its own. Consumers who need structured `OS_LOG_TYPE_*` routing
 * should inject their own [KompressorLogger] that calls `os_log_with_type` via cinterop.
 *
 * This is the **only** file in `kompressor/src` permitted to call `NSLog`;
 * see `scripts/check-no-raw-logging.sh`. Every other log emission must route through a
 * [KompressorLogger] instance.
 */
@Suppress("FunctionNaming") // See commonMain PlatformLogger.kt for the rationale.
public actual fun PlatformLogger(): KompressorLogger = IosPlatformLogger

private object IosPlatformLogger : KompressorLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val levelTag = when (level) {
            LogLevel.VERBOSE -> "V"
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        val suffix = throwable?.let { " | ${it::class.simpleName}: ${it.message ?: ""}" }.orEmpty()
        // %@ (not %s) so Foundation formats the Kotlin String via NSString bridging — %s
        // expects a C string and prints garbage for UTF-8 Kotlin strings.
        NSLog("[$levelTag] $tag: $message$suffix")
    }
}
