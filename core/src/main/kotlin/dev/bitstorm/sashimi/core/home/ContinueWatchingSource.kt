package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Where Home's Continue Watching row comes from. The server's Continue Watching
 * plugin (bitstorm-labs/jellyfin-plugin-continue-watching) ranks it, so every
 * client shows the same order; without the plugin the row is merged here from
 * Resume and Next Up by [ContinueWatchingMerger].
 */
object ContinueWatchingSource {
    suspend fun load(client: JellyfinClient): List<BaseItemDto> =
        coroutineScope {
            runCatching { client.getContinueWatching() }.getOrNull()?.let { return@coroutineScope it }

            // Each list is tolerant on its own: one failing call leaves the others.
            val resume = async { runCatching { client.getResumeItems() }.getOrDefault(emptyList()) }
            val nextUp = async { runCatching { client.getNextUp() }.getOrDefault(emptyList()) }
            // Optional: without it the row still loads, just ordered by Next Up alone.
            val recent = async { runCatching { client.getRecentlyPlayedEpisodes() }.getOrDefault(emptyList()) }
            ContinueWatchingMerger.merge(
                resume.await(),
                nextUp.await(),
                ContinueWatchingMerger.seriesLastPlayed(recent.await()),
            )
        }
}
