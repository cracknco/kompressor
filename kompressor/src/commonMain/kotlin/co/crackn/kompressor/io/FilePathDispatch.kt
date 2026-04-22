/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

/**
 * Shared dispatch helper: resolve a [MediaSource] to a filesystem path or throw a typed
 * [UnsupportedOperationException] pointing at the Linear ticket where the missing variant
 * will be implemented.
 *
 * Called by the new `compress(MediaSource, MediaDestination, ...)` overloads on all six
 * platform compressors (Android + iOS × image / audio / video) so the dispatch table stays
 * in one place — a new variant in one platform impl would otherwise risk drifting from
 * its sibling.
 *
 * Throws rather than returning `String?` so a `suspendRunCatching` wrapper captures the
 * typed failure into `Result.failure` with zero additional boilerplate in each call site.
 */
internal fun MediaSource.requireFilePathOrThrow(): String = when (this) {
    is MediaSource.Local.FilePath -> path
    is MediaSource.Local.Stream -> throw UnsupportedOperationException(STREAM_INPUT_MSG)
    is MediaSource.Local.Bytes -> throw UnsupportedOperationException(BYTES_INPUT_MSG)
    // Platform subtypes (Android `AndroidUriMediaSource`, iOS future siblings) land here when a
    // caller passes an Android-built `MediaSource` to the iOS compressor (or vice versa). The
    // Android compressors route through `toAndroidInputPath()` instead of this helper; iOS
    // sibling (T5) will add an equivalent `toIosInputPath()`. Until then, fail loudly with the
    // wrapper class name so the caller knows which builder produced the mismatch.
    else -> throw UnsupportedOperationException(
        "Unsupported MediaSource subtype for this platform: ${this::class.simpleName}",
    )
}

/**
 * Sibling of [requireFilePathOrThrow] for the output side. Mirrors the same "FilePath only
 * for now, all other variants throw with a CRA ticket reference" contract.
 */
internal fun MediaDestination.requireFilePathOrThrow(): String = when (this) {
    is MediaDestination.Local.FilePath -> path
    is MediaDestination.Local.Stream -> throw UnsupportedOperationException(STREAM_OUTPUT_MSG)
    else -> throw UnsupportedOperationException(
        "Unsupported MediaDestination subtype for this platform: ${this::class.simpleName}",
    )
}

private const val STREAM_INPUT_MSG =
    "MediaSource.Local.Stream input will be supported in CRA-95. " +
        "For now, use MediaSource.Local.FilePath."
private const val BYTES_INPUT_MSG =
    "MediaSource.Local.Bytes input will be supported in CRA-95. " +
        "For now, use MediaSource.Local.FilePath."
private const val STREAM_OUTPUT_MSG =
    "MediaDestination.Local.Stream output will be supported in CRA-95. " +
        "For now, use MediaDestination.Local.FilePath."
