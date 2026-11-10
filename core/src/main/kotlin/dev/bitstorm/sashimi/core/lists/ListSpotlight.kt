package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.shuffle.TvShuffle
import dev.bitstorm.sashimi.core.shuffle.TvShuffleSource
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap

/**
 * One poster of a [ListCover.Stack], in units of the front poster: [offsetX]
 * is a fraction of its width, [offsetY] of its height. [depth] 0 is the front.
 */
data class StackLayer(
    val depth: Int,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
    val rotation: Float,
    val alpha: Float,
)

/**
 * The poster-stack cover (watchlist v2 §6, list-covers mockup option A): the
 * first poster full size and sharp, the next two fanned behind it, smaller,
 * further right and turned about 3° and 7°, the back one slightly faded.
 * Focus fans the stack out a little. Pure, so Apple, Android and Roku draw
 * the same stack.
 */
object PosterStack {
    const val MAX = 3

    /** The cover's width in front-poster widths; every layer, fanned or not, fits in it. */
    const val WIDTH_IN_POSTERS = 1.7f

    private val rest =
        listOf(
            StackLayer(depth = 0, offsetX = 0f, offsetY = 0f, scale = 1f, rotation = 0f, alpha = 1f),
            StackLayer(depth = 1, offsetX = 0.27f, offsetY = 0.03f, scale = 0.92f, rotation = 3f, alpha = 1f),
            StackLayer(depth = 2, offsetX = 0.5f, offsetY = 0.06f, scale = 0.88f, rotation = 7f, alpha = 0.85f),
        )

    private const val FAN_SPREAD = 1.3f
    private const val FAN_TURN = 2f

    /** Back to front, so drawing them in order puts the first poster on top. An empty list draws one placeholder. */
    fun layers(
        count: Int,
        fanned: Boolean,
    ): List<StackLayer> =
        rest
            .take(count.coerceIn(1, MAX))
            .map { layer ->
                if (!fanned || layer.depth == 0) {
                    layer
                } else {
                    layer.copy(offsetX = layer.offsetX * FAN_SPREAD, rotation = layer.rotation + FAN_TURN)
                }
            }.reversed()
}

/** What the Spotlight says about the selected title. */
data class SpotlightInfo(
    val title: String,
    /** "1983", "3h 13m", "PG", "Drama, History": only what the server has. */
    val facts: List<String>,
    val overview: String?,
)

/**
 * The Spotlight list page (watchlist v2 §6, list-layouts mockup option A):
 * the selected title fills the top with its backdrop, details and actions;
 * the list's posters run below it.
 */
object ListSpotlight {
    private const val TICKS_PER_MINUTE = 600_000_000L
    private const val GENRES = 2

    fun info(item: BaseItemDto): SpotlightInfo {
        val facts =
            buildList {
                if (item.type == ItemType.EPISODE) {
                    val s = item.parentIndexNumber
                    val e = item.indexNumber
                    add(if (s != null && e != null) "S$s:E$e · ${item.name}" else item.name)
                } else {
                    item.displayYear?.let { add(it.toString()) }
                }
                if (item.type == ItemType.SERIES) {
                    item.childCount?.takeIf { it > 0 }?.let { add(if (it == 1) "1 season" else "$it seasons") }
                } else {
                    item.runTimeTicks?.takeIf { it > 0 }?.let { add(runtime(it)) }
                }
                item.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.genres
                    ?.filter { it.isNotBlank() }
                    ?.take(GENRES)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it.joinToString(", ")) }
            }.filter { it.isNotBlank() }
        val title = if (item.type == ItemType.EPISODE) item.seriesName ?: item.name else item.name
        return SpotlightInfo(title, facts, item.overview?.takeIf { it.isNotBlank() })
    }

    /** A Watchlist title the server doesn't have: what its entry knows. */
    fun info(entry: WatchlistEntry): SpotlightInfo =
        SpotlightInfo(
            title = entry.title,
            facts = listOfNotNull(entry.year?.toString(), if (entry.isSeries) "Show" else "Movie"),
            overview = null,
        )

    /** "3h 13m", or "50 min" under an hour. */
    fun runtime(ticks: Long): String {
        val minutes = ticks / TICKS_PER_MINUTE
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
    }

    /** The title in the spotlight: [current] while it's still on the list, else the first. */
    fun selected(
        keys: List<String>,
        current: String?,
    ): String? = current?.takeIf { it in keys } ?: keys.firstOrNull()

    /** What Play starts: a movie or episode itself, a show at its next episode. Null: nothing to play. */
    suspend fun playTarget(
        item: BaseItemDto,
        source: TvShuffleSource,
    ): String? =
        when (item.type) {
            ItemType.SERIES -> TvShuffle.nextEpisode(item.id, source)?.id
            else -> item.id
        }
}

/**
 * The full item behind each spotlighted title (a list's own entries carry no
 * overview, and a series card is built from its first episode). Fetched once
 * per title; a failure isn't kept, so the next look tries again.
 */
class SpotlightDetails(
    private val fetch: suspend (String) -> BaseItemDto?,
) {
    private val cache = ConcurrentHashMap<String, BaseItemDto>()

    fun cached(id: String): BaseItemDto? = cache[id]

    suspend fun load(id: String): BaseItemDto? {
        cache[id]?.let { return it }
        val item = runCatchingCancellable { fetch(id) }.getOrNull() ?: return null
        cache[id] = item
        return item
    }
}
