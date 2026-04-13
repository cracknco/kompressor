@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import platform.Foundation.NSFileManager

/**
 * iOS mirror of the Android `deletingOutputOnFailure` guard. Runs [block] and, if it throws
 * (including via `CancellationException`), removes the file at [outputPath] before rethrowing
 * so callers don't observe partial / corrupt output from cancelled or failed compressions.
 *
 * Cleanup uses `NSFileManager.removeItemAtPath:error:` and swallows any removal failure
 * (filesystem error, permission issue) — `File.exists()`-grade strict invariants remain the
 * caller's responsibility.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> deletingOutputOnFailure(outputPath: String, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        runCatching { NSFileManager.defaultManager.removeItemAtPath(outputPath, null) }
        throw t
    }
