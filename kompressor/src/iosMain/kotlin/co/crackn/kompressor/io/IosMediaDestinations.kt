/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import platform.Foundation.NSURL

/**
 * Create a [MediaDestination] from a file-system [NSURL].
 *
 * Accepts `file://` URLs only — sibling of [MediaSource.Companion.of] `(url: NSURL)`. Remote
 * schemes `http` / `https` are rejected by design:
 * ```
 * IllegalArgumentException: Remote URLs not supported. Write locally first then upload.
 * ```
 * The rejection message is a cross-platform invariant shared with the Android builder sibling
 * (CRA-93) so a consumer switching platforms sees byte-identical text. The constant lives in
 * [MediaSourceRejections] (commonMain).
 *
 * The returned [MediaDestination.Local] is consumed by the
 * `compress(MediaSource, MediaDestination, ...)` overloads introduced in CRA-92; `file://` URLs
 * are unwrapped to their filesystem path and written directly by the existing iOS compressor
 * pipeline (`UIImageJPEGRepresentation` / `CGImageDestinationCreateWithURL` / `AVAssetWriter`).
 *
 * @param url The `file://` URL to write the compressed output to.
 * @return A [MediaDestination.Local] that the iOS compressors recognise as an NSURL destination.
 * @throws IllegalArgumentException if [url]'s scheme is `http` / `https`, or if the scheme is
 *   otherwise unsupported (null, custom scheme Kompressor does not resolve).
 */
public fun MediaDestination.Companion.of(url: NSURL): MediaDestination.Local =
    when (url.scheme) {
        "file" -> IosUrlMediaDestination(url)
        // See [MediaSource.Companion.of(NSURL)] for the `rejectScheme` helper rationale — same
        // Nothing-returning pattern so the expression-bodied `when` unifies at
        // `MediaDestination.Local` instead of `Any`. The rejection message is the cross-platform
        // invariant consumed by the Android sibling (CRA-93) — keep byte-identical via
        // [MediaSourceRejections.REMOTE_URL_OUTPUT].
        "http", "https" -> rejectDestinationScheme(MediaSourceRejections.REMOTE_URL_OUTPUT)
        null -> rejectDestinationScheme("Unsupported NSURL scheme: <null>. Expected 'file'.")
        else -> rejectDestinationScheme("Unsupported NSURL scheme: ${url.scheme}. Expected 'file'.")
    }

/**
 * Sibling of [IosMediaSources]' private `rejectScheme`; see that file for rationale. A distinct
 * name (vs. reusing the Sources helper) avoids a `private` name-collision across sibling files
 * in the same package — Kotlin's `private` scope is the file, not the package.
 */
@Suppress("TooGenericExceptionThrown")
private fun rejectDestinationScheme(message: String): Nothing = throw IllegalArgumentException(message)

/**
 * Internal marker wrapper for `file://` NSURL outputs. The dispatch in [toIosOutputHandle]
 * unwraps it back to a filesystem path the legacy `compress(inputPath, outputPath, ...)`
 * overloads already know how to handle. No temp-file materialization is required for `file://`
 * URLs; the iOS compressor writes directly to the unwrapped path.
 */
internal class IosUrlMediaDestination(val url: NSURL) : MediaDestination.Local {
    override fun equals(other: Any?): Boolean = other is IosUrlMediaDestination && other.url == url
    override fun hashCode(): Int = url.hashCode()
    override fun toString(): String = "IosUrlMediaDestination(url=$url)"
}
