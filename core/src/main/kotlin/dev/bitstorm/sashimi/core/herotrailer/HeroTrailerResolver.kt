package dev.bitstorm.sashimi.core.herotrailer

import dev.bitstorm.sashimi.core.util.runCatchingCancellable

/**
 * Finds the trailer behind a source id (a movie or series), once per process.
 *
 * A miss is cached as well as a hit, so a title with no trailer is not asked
 * again on every visit. A failed request is not cached: that means "could not
 * ask", not "there is none", and the next visit asks again.
 *
 * Not thread-safe by design: the controller calls it on the main thread.
 */
class HeroTrailerResolver(
    private val lookup: suspend (sourceId: String) -> HeroTrailerSource?,
) {
    private val cache = mutableMapOf<String, HeroTrailerSource?>()

    suspend fun resolve(sourceId: String): HeroTrailerSource? {
        if (cache.containsKey(sourceId)) return cache[sourceId]
        val result = runCatchingCancellable { lookup(sourceId) }
        if (result.isFailure) return null
        return result.getOrNull().also { cache[sourceId] = it }
    }
}
