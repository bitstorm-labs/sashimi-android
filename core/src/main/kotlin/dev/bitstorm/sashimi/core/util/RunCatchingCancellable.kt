package dev.bitstorm.sashimi.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending code.
 *
 * Kotlin's `runCatching` catches `Throwable`, which includes
 * [CancellationException]. Around a suspend call that is a structured-concurrency
 * bug rather than a style question: a cancelled coroutine stops dying at its
 * suspension point, resumes with a `Result.failure`, substitutes a fallback and
 * carries on writing state it should no longer own.
 *
 * The concrete damage this caused: every keystroke typed during an in-flight
 * search cancelled the previous one, whose coroutine then resumed, substituted
 * an empty list and set `isSearching = false` -- so the spinner vanished and the
 * PREVIOUS query's results were presented as the answer for the new text.
 *
 * Use this for any `runCatching` that wraps a suspend call. The plain version is
 * still correct around genuinely synchronous work.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
