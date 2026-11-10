package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import kotlinx.serialization.Serializable

/**
 * One title from `GET /SashimiLists/Watchlist/Search` (plugin 0.3.0, the
 * watchlist v2 API contract). [itemId] is set in "On your server" and null in
 * "Not on server". Its fields are the add body's, so ＋ posts it as is.
 */
@Serializable
data class WatchlistSearchHit(
    val type: String,
    val title: String,
    val year: Int? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val imdbId: String? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val itemId: String? = null,
    val onWatchlist: Boolean = false,
    val watchlistEntryId: String? = null,
) {
    val isSeries: Boolean get() = type == Watchlist.SERIES
}

/** The search's answer: [query] is what the server searched, to drop a stale reply. */
@Serializable
data class WatchlistSearchResult(
    val query: String,
    val onServer: List<WatchlistSearchHit> = emptyList(),
    val notOnServer: List<WatchlistSearchHit> = emptyList(),
)

/**
 * `GET /SashimiLists/Watchlist/Details`: a title the server doesn't have, from
 * its metadata providers. Only [type], [title] and [onWatchlist] are certain;
 * the provider may lack anything else. [itemId] set means it is on the server.
 */
@Serializable
data class WatchlistTitleDetails(
    val type: String,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonCount: Int? = null,
    val officialRating: String? = null,
    val genres: List<String>? = null,
    val communityRating: Double? = null,
    val source: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val imdbId: String? = null,
    val itemId: String? = null,
    val onWatchlist: Boolean = false,
    val watchlistEntryId: String? = null,
) {
    val isSeries: Boolean get() = type == Watchlist.SERIES
}

/** `GET /SashimiLists/Config`: settings the server's admin sets for every app. */
@Serializable
data class SashimiServerConfig(
    val requestsUrl: String? = null,
)

/**
 * Which title a details page is about: its kind and every id known. Built from
 * a Watchlist entry or a search hit; it is also what the page's route carries.
 */
data class WatchlistTitleRef(
    val type: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val imdbId: String? = null,
) {
    val isSeries: Boolean get() = type == Watchlist.SERIES

    /** The query for `/Watchlist/Details`; empty ids are left out. */
    fun query(): List<Pair<String, String>> =
        listOfNotNull(
            "type" to if (isSeries) "series" else "movie",
            tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdbId" to it },
            tvdbId?.takeIf { it.isNotBlank() }?.let { "tvdbId" to it },
            imdbId?.takeIf { it.isNotBlank() }?.let { "imdbId" to it },
        )

    /** What Add stores when the details never came (an older plugin, or no provider knows it). */
    fun newEntry(): NewWatchlistEntry = NewWatchlistEntry(type, title, year, posterUrl, tmdbId, tvdbId, imdbId)

    /** The Seerr page for Request: Seerr's ids are TMDB, so none without one. */
    fun seerrTarget(): SeerrTarget? {
        val tmdb = tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return SeerrTarget(if (isSeries) SeerrMediaKind.TV else SeerrMediaKind.MOVIE, tmdb, title)
    }

    companion object {
        fun of(entry: WatchlistEntry) =
            WatchlistTitleRef(entry.type, entry.title, entry.year, entry.posterUrl, entry.tmdbId, entry.tvdbId, entry.imdbId)

        fun of(hit: WatchlistSearchHit) =
            WatchlistTitleRef(hit.type, hit.title, hit.year, hit.posterUrl, hit.tmdbId, hit.tvdbId, hit.imdbId)
    }
}

/**
 * Watchlist v2's rules (design 2026-11-10 §2–§4). Pure, so the same test
 * vectors run on Apple, Android and Roku.
 */
object WatchlistV2 {
    const val MIN_QUERY = 2
    const val MAX_QUERY = 200

    /** Searching waits for two characters; the server refuses over 200. */
    fun searchable(text: String): Boolean = text.trim().length in MIN_QUERY..MAX_QUERY

    /** A reply for a query the box no longer holds is dropped. */
    fun isCurrent(
        result: WatchlistSearchResult,
        text: String,
    ): Boolean = result.query == text.trim()

    fun newEntry(hit: WatchlistSearchHit): NewWatchlistEntry =
        NewWatchlistEntry(hit.type, hit.title, hit.year, hit.posterUrl, hit.tmdbId, hit.tvdbId, hit.imdbId)

    fun newEntry(details: WatchlistTitleDetails): NewWatchlistEntry =
        NewWatchlistEntry(details.type, details.title, details.year, details.posterUrl, details.tmdbId, details.tvdbId, details.imdbId)

    /** A search hit's key, for the page's own "✓ Added" memory. */
    fun key(hit: WatchlistSearchHit): String = listOf(hit.type, hit.tmdbId, hit.tvdbId, hit.imdbId, hit.itemId).joinToString("|")

    /** "2021 · 2h 35m · PG-13 · Science Fiction, Adventure · ★ 7.8": only what the provider knew. */
    fun facts(details: WatchlistTitleDetails): List<String> =
        buildList {
            details.year?.let { add(it.toString()) }
            if (details.isSeries) {
                details.seasonCount?.takeIf { it > 0 }?.let { add(if (it == 1) "1 season" else "$it seasons") }
            } else {
                details.runtimeMinutes?.takeIf { it > 0 }?.let { add(ListSpotlight.runtime(it * 600_000_000L)) }
            }
            details.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
            details.genres
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(it.joinToString(", ")) }
            details.communityRating?.takeIf { it > 0 }?.let { add("★ ${"%.1f".format(java.util.Locale.US, it)}") }
        }

    /** A search result's second line: "2025 · Movie", "2017 · Show". */
    fun hitLine(hit: WatchlistSearchHit): String =
        listOfNotNull(hit.year?.toString(), if (hit.isSeries) "Show" else "Movie").joinToString(" · ")
}
