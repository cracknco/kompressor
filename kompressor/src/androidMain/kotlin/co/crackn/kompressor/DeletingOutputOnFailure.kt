package co.crackn.kompressor

import java.io.File

/**
 * Runs [block] and, if it throws for any reason (including [kotlinx.coroutines.CancellationException]),
 * deletes the file at [outputPath] before rethrowing.
 *
 * Transcoding pipelines (video or audio) write to [outputPath] incrementally — a mid-export failure
 * or cancellation leaves a partial, corrupt file on disk. This guard makes the failure path atomic
 * from the caller's perspective: either the file exists and is valid, or it does not exist.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> deletingOutputOnFailure(outputPath: String, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        runCatching { File(outputPath).delete() }
        throw t
    }
