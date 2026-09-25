package dev.bitstorm.sashimi.core.shuffle

import dev.bitstorm.sashimi.core.home.NextUpSelector
import dev.bitstorm.sashimi.core.home.isSpecial
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.runCatchingCancellable

/** How a TV library's Shuffle button picks what to play (sashimi-roku#141). */
enum class TvShuffleMode(
    val key: String,
    val label: String,
) {
    /** Any episode in the library, at random. The original behaviour. */
    RANDOM_EPISODE("randomEpisode", "Random Episode"),

    /** A random series, then the episode its Play button would start. */
    RANDOM_SHOW_NEXT_EPISODE("randomShowNextEpisode", "Random Show, Next Episode"),
    ;

    companion object {
        /** Random Episode: what Shuffle always did, so existing installs are unchanged. */
        val DEFAULT = RANDOM_EPISODE

        /** The stored [key] back to a mode; unknown or missing is [DEFAULT]. */
        fun fromKey(key: String?): TvShuffleMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The server calls a library Shuffle needs, so [TvShuffle] is testable without a server. */
interface TvShuffleSource {
    suspend fun randomItem(
        parentId: String,
        includeTypes: List<ItemType>,
    ): BaseItemDto?

    /** The server's Next Up episode for one series, if any. */
    suspend fun nextUpEpisode(seriesId: String): BaseItemDto?

    /** Every episode of a series in season order (Season 0 included). */
    suspend fun episodes(seriesId: String): List<BaseItemDto>
}

/** [TvShuffleSource] backed by the real [JellyfinClient]. */
class JellyfinTvShuffleSource(
    private val client: JellyfinClient,
) : TvShuffleSource {
    override suspend fun randomItem(
        parentId: String,
        includeTypes: List<ItemType>,
    ) = client.getRandomItem(parentId, includeTypes)

    override suspend fun nextUpEpisode(seriesId: String) = client.getNextUp(limit = 1, seriesId = seriesId).firstOrNull()

    override suspend fun episodes(seriesId: String) = client.getEpisodes(seriesId)
}

/** Picks what a library's Shuffle button plays. */
object TvShuffle {
    /**
     * [includeTypes] is what the library shuffles: `[EPISODE]` for a TV
     * library, the only case [mode] applies to. Movie libraries always pick at
     * random. A series' own Shuffle doesn't come through here: inside one show
     * "random show" means nothing.
     */
    suspend fun pick(
        libraryId: String,
        includeTypes: List<ItemType>,
        mode: TvShuffleMode,
        source: TvShuffleSource,
    ): BaseItemDto? {
        if (includeTypes != listOf(ItemType.EPISODE) || mode != TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE) {
            return source.randomItem(libraryId, includeTypes)
        }
        val series = source.randomItem(libraryId, listOf(ItemType.SERIES)) ?: return null
        return nextEpisode(series.id, source)
    }

    /**
     * The episode the series' Play button starts: the server's Next Up, else
     * the first unwatched regular episode, else (all watched) the first regular
     * episode. Specials never win while a regular episode is unwatched.
     */
    suspend fun nextEpisode(
        seriesId: String,
        source: TvShuffleSource,
    ): BaseItemDto? {
        val nextUp = runCatchingCancellable { source.nextUpEpisode(seriesId) }.getOrNull()
        if (nextUp != null && !nextUp.isSpecial) return nextUp
        val episodes = source.episodes(seriesId)
        val regular = episodes.filter { !it.isSpecial }
        // Every regular episode watched: an unwatched special Next Up offers is
        // next, otherwise the show starts over. A specials-only show plays its first.
        return NextUpSelector.firstUnwatched(regular)
            ?: nextUp
            ?: regular.firstOrNull()
            ?: NextUpSelector.firstUnwatched(episodes)
            ?: episodes.firstOrNull()
    }
}
