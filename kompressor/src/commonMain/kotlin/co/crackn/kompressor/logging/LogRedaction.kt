/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

/**
 * Basename-only renderer for filesystem paths that flow into library-internal log lines.
 *
 * Consumer apps wire [KompressorLogger] into pipelines that often ship off-device (Crashlytics,
 * Sentry breadcrumbs, OSLog / Logcat streamed to a backend). A raw Android path such as
 * `/storage/emulated/0/Android/data/com.acme.gallery/files/user_42_birthday.mp4` leaks both the
 * app's private data directory layout *and* the end-user filename; an iOS PHAsset temp path
 * (`NSTemporaryDirectory()/…-PHAsset-ABCD-…`) correlates back to a specific photo-library entry.
 *
 * Stripping to the basename is the pragmatic middle ground: enough signal for a developer reading
 * the log ("was it an mp4 or an m4a?", "was this a PHAsset temp file?") without exposing the
 * containing directory tree. Every [SafeLogger.instrumentCompress] call site in the library goes
 * through this helper before interpolating `inputPath` / `outputPath` into a log message.
 *
 * Companion to [CRA-47](https://linear.app/crackn/issue/CRA-47)'s raw-logging gate — that rule
 * prevents `println` / `android.util.Log` / `NSLog` leaks; this rule prevents the same class of
 * leak through the pluggable-logger channel.
 *
 * @return `"<empty>"` when [path] is empty **or** the basename after
 *   [kotlin.text.substringAfterLast] is empty (e.g. `"/some/dir/"` with a trailing separator);
 *   otherwise the last path segment, truncated to 48 characters followed by `…(N more)` when the
 *   segment exceeds 64 characters. The truncation threshold leaves room for typical
 *   timestamped / UUID-bearing filenames (e.g. `IMG_20260423_142501_12345.jpg`) while cutting
 *   off pathologically long names. Collapsing both empty cases onto the same `<empty>` sentinel
 *   is deliberate: a blank field in a log line is less useful than an explicit
 *   "basename unresolvable" marker.
 */
internal fun redactPath(path: String): String = when {
    path.isEmpty() -> "<empty>"
    else -> path.substringAfterLast('/')
        .let { base ->
            when {
                base.isEmpty() -> "<empty>"
                base.length > REDACT_MAX_LEN ->
                    "${base.take(REDACT_KEEP_LEN)}…(${base.length - REDACT_KEEP_LEN} more)"
                else -> base
            }
        }
}

/** Basename longer than this triggers truncation. */
private const val REDACT_MAX_LEN: Int = 64

/** Characters retained from the start of an over-long basename before the `…(N more)` suffix. */
private const val REDACT_KEEP_LEN: Int = 48
