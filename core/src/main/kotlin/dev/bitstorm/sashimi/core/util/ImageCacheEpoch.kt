package dev.bitstorm.sashimi.core.util

/**
 * One-time image-cache resets. Bump [CURRENT] when cached images must be thrown
 * away on the next launch; the app stores the last epoch it cleared for.
 *
 * Epoch 1 (0.9.0): image URLs gained the server's `tag=`. Pictures cached under
 * the old untagged URLs are never requested again, but the disk cache still
 * holds them (wasted space), and any untagged URL that remains would keep the
 * stale picture, so the cache is dropped once.
 */
object ImageCacheEpoch {
    const val CURRENT = 1

    /** True when the stored epoch is behind [CURRENT] (0 = never cleared). */
    fun needsClear(storedEpoch: Int): Boolean = storedEpoch < CURRENT
}
