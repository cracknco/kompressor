/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

import android.util.Log

/**
 * Android [PlatformLogger] actual — delegates to [android.util.Log].
 *
 * This is the **only** file in `kompressor/src` permitted to import `android.util.Log`;
 * see `scripts/check-no-raw-logging.sh`. Every other log emission must route through a
 * [KompressorLogger] instance.
 */
@Suppress("FunctionNaming") // See commonMain PlatformLogger.kt for the rationale.
public actual fun PlatformLogger(): KompressorLogger = AndroidPlatformLogger

private object AndroidPlatformLogger : KompressorLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.VERBOSE -> if (throwable == null) Log.v(tag, message) else Log.v(tag, message, throwable)
            LogLevel.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            LogLevel.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            LogLevel.WARN -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            LogLevel.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
