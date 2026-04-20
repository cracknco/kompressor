/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

/**
 * Returns the default [KompressorLogger] for the current platform.
 *
 * * **Android**: routes every record to [android.util.Log] using the matching priority level
 *   ([android.util.Log.v] / [android.util.Log.d] / [android.util.Log.i] / [android.util.Log.w] /
 *   [android.util.Log.e]). Logcat's runtime filter decides which levels are actually visible —
 *   the library sends everything; the platform throttles by build type (VERBOSE / DEBUG are
 *   typically hidden on release builds per Android tooling defaults).
 * * **iOS**: routes every record through `NSLog`, which delegates to Apple's unified logging
 *   system (`os_log` infrastructure). Messages reach Console.app, device syslog, and
 *   `OSLogStore` consumers. INFO / WARN / ERROR show by default; VERBOSE / DEBUG are prefixed
 *   with their level so filtered views can recognise them.
 *
 * Returns a fresh instance on each call; callers who need a singleton should capture the result.
 *
 * The returned instance is **thread-safe** and **no-throw** per the [KompressorLogger] contract.
 *
 * ### When to replace
 *
 * The default is intentionally chatty-but-readable. Prefer a custom logger when:
 *
 * * Your app already standardises on a logging framework (Timber, SwiftLog, CocoaLumberjack).
 * * You want to ship logs to a crash / APM backend (Sentry, Datadog, Firebase Crashlytics).
 * * You want full silence — pass [NoOpLogger] instead.
 *
 * See `docs/logging.md` for integration recipes.
 */
// PascalCase factory mirroring Kotlin stdlib style (`Lazy`, `MutableStateFlow`) — intentional,
// keeps parity with `createKompressor()`'s companion-free factory idiom.
@Suppress("FunctionNaming")
public expect fun PlatformLogger(): KompressorLogger
