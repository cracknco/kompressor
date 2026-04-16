/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import java.io.File

/**
 * Runs [block] and, if it throws for any reason (including [kotlinx.coroutines.CancellationException]),
 * deletes the file at [outputPath] before rethrowing.
 *
 * Transcoding pipelines (video or audio) write to [outputPath] incrementally — a mid-export failure
 * or cancellation leaves a partial, corrupt file on disk. This guard best-effort cleans up that
 * partial file before rethrowing so callers are unlikely to observe corrupt output.
 *
 * Note: cleanup is best-effort — if [File.delete] itself fails (e.g. filesystem error) the
 * deletion failure is silently swallowed and the partial file may remain on disk. Callers who
 * need a strict "output absent on failure" invariant must verify via [File.exists] themselves.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> deletingOutputOnFailure(outputPath: String, block: () -> T): T {
    // Snapshot whether the path existed (and as what) BEFORE invoking the block so we don't
    // clobber caller-owned artifacts on the failure path. The cleanup contract is: "delete the
    // file the block was about to produce" — if the path is a pre-existing directory or other
    // non-file entry, leave it alone and let the underlying compress() error speak for itself.
    val output = File(outputPath)
    val preExistedAsNonFile = output.exists() && !output.isFile
    return try {
        block()
    } catch (t: Throwable) {
        if (!preExistedAsNonFile) {
            runCatching { output.delete() }
        }
        throw t
    }
}
