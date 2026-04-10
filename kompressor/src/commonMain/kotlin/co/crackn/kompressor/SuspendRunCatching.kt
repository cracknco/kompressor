package co.crackn.kompressor

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but rethrows [CancellationException] so that
 * structured concurrency is not broken.
 */
internal inline fun <R> suspendRunCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        Result.failure(e)
    }
}
