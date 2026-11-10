package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrImages
import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import dev.bitstorm.sashimi.core.seerr.SeerrTitleDetails
import kotlinx.serialization.Serializable

/** The Watchlist's Plex link as the Sashimi Lists plugin reports it. Never carries the token. */
@Serializable
data class PlexLink(
    val username: String,
    val needsReconnect: Boolean = false,
)

/** One Watchlist title. [itemId] is the library item this user can see (dashless), null when not on the server. */
@Serializable
data class WatchlistEntry(
    val id: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val imdbId: String? = null,
    val addedAt: String,
    val itemId: String? = null,
) {
    val isSeries: Boolean get() = type == Watchlist.SERIES
}

/** `GET /SashimiLists/Watchlist`: entries newest first. */
@Serializable
data class WatchlistView(
    val plex: PlexLink? = null,
    val cardDismissed: Boolean = false,
    /** The person disconnected Plex (plugin 0.2.2+): the Requests auto-link must not reconnect them. */
    val plexOptedOut: Boolean = false,
    val entries: List<WatchlistEntry> = emptyList(),
)

/** The body of `POST /SashimiLists/Watchlist/Items`. */
@Serializable
data class NewWatchlistEntry(
    val type: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val imdbId: String? = null,
)

/** What reading the Watchlist came to. Only [Absent] (a 404) means the server has none. */
sealed interface WatchlistRead {
    /** Not answered yet. */
    data object Pending : WatchlistRead

    data class Ready(
        val view: WatchlistView,
    ) : WatchlistRead

    /** 404: the plugin isn't installed (or is too old). */
    data object Absent : WatchlistRead

    /** A network error or a 5xx: the Watchlist may well be there. */
    data class Failed(
        val error: Throwable,
    ) : WatchlistRead
}

/** What tapping a Watchlist tile does. */
sealed interface WatchlistTap {
    /** On the server: its detail page. */
    data class Open(
        val itemId: String,
    ) : WatchlistTap

    /** Not on the server: its title page (watchlist v2 §3), with the Watchlist toggle and Request. */
    data class Details(
        val ref: WatchlistTitleRef,
    ) : WatchlistTap
}

/** How a title the server doesn't have can be asked for. */
sealed interface WatchlistRequest {
    /** Signed into Requests: its Seerr page, with Request. */
    data class Seerr(
        val target: SeerrTarget,
    ) : WatchlistRequest

    /** Signed into Requests, but there's no TMDB id to ask Seerr with. */
    data object Unavailable : WatchlistRequest

    /** No Requests sign-in on this device. */
    data object SignIn : WatchlistRequest
}

/** The pill on a Watchlist tile for a title the server doesn't have. */
enum class WatchlistTag(
    val label: String,
) {
    NOT_ON_SERVER("Not on server"),
    REQUESTED("Requested"),
}

/**
 * The Watchlist's rules (watchlist design, 2026-11-09). Pure, so the same test
 * vectors run on Apple, Android and Roku. Matching is the plugin's rule: any
 * shared external id (IMDb ignoring case).
 */
object Watchlist {
    const val NAME = "Watchlist"
    const val MOVIE = "Movie"
    const val SERIES = "Series"

    private fun providerId(
        item: BaseItemDto,
        key: String,
    ): String? =
        item.providerIds
            ?.entries
            ?.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

    /** Movies and shows with a TMDB, TVDB or IMDb id: anything else Plex couldn't hold, so it's never offered. */
    fun eligible(item: BaseItemDto): Boolean =
        (item.type == ItemType.MOVIE || item.type == ItemType.SERIES) &&
            listOf("Tmdb", "Tvdb", "Imdb").any { providerId(item, it) != null }

    /** No poster: a Jellyfin image URL dies with the title, and the plugin fills one from TMDB. */
    fun newEntry(item: BaseItemDto): NewWatchlistEntry? {
        if (!eligible(item)) return null
        return NewWatchlistEntry(
            type = if (item.type == ItemType.SERIES) SERIES else MOVIE,
            title = item.name,
            year = item.productionYear,
            posterUrl = null,
            tmdbId = providerId(item, "Tmdb"),
            tvdbId = providerId(item, "Tvdb"),
            imdbId = providerId(item, "Imdb"),
        )
    }

    fun newEntry(details: SeerrTitleDetails): NewWatchlistEntry =
        NewWatchlistEntry(
            type = if (details.kind == SeerrMediaKind.TV) SERIES else MOVIE,
            title = details.title,
            year = details.year,
            posterUrl = SeerrImages.poster(details.posterPath, 500),
            tmdbId = details.tmdbId.toString(),
            tvdbId = details.tvdbId?.toString(),
            imdbId = details.imdbId,
        )

    fun matches(
        entry: WatchlistEntry,
        candidate: NewWatchlistEntry,
    ): Boolean =
        same(entry.tmdbId, candidate.tmdbId) ||
            same(entry.tvdbId, candidate.tvdbId) ||
            (entry.imdbId != null && candidate.imdbId != null && entry.imdbId.equals(candidate.imdbId, ignoreCase = true))

    private fun same(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && a == b

    fun alreadyOn(
        view: WatchlistView,
        item: BaseItemDto,
    ): Boolean {
        val candidate = newEntry(item)
        return view.entries.any { SharedLists.sameId(it.itemId, item.id) || (candidate != null && matches(it, candidate)) }
    }

    /** Null for a title on the server. A null [seerrBadge] means no Seerr session or no answer. */
    fun tag(
        entry: WatchlistEntry,
        seerrBadge: SeerrBadge?,
    ): WatchlistTag? =
        when {
            entry.itemId != null -> null
            seerrBadge == SeerrBadge.PENDING || seerrBadge == SeerrBadge.COMING -> WatchlistTag.REQUESTED
            else -> WatchlistTag.NOT_ON_SERVER
        }

    /** The Seerr page for a title; null without a numeric TMDB id, so nothing ever opens tmdb 0. */
    fun seerrTarget(entry: WatchlistEntry): SeerrTarget? = WatchlistTitleRef.of(entry).seerrTarget()

    /**
     * Whether Add to List offers the Watchlist. [target] is the library item,
     * or null for a Seerr title (always a movie or show with a TMDB id).
     */
    fun pickerShowsWatchlist(
        target: BaseItemDto?,
        read: WatchlistRead,
    ): Boolean = read !is WatchlistRead.Absent && (target == null || eligible(target))

    /** A Seerr title isn't a library item, so only the Watchlist can take it. */
    fun pickerShowsLists(isSeerr: Boolean): Boolean = !isSeerr

    /** A title on the server opens its page; one that isn't opens its title page (replacing v1's Seerr page or note). */
    fun tapAction(entry: WatchlistEntry): WatchlistTap =
        entry.itemId?.let { WatchlistTap.Open(it) } ?: WatchlistTap.Details(WatchlistTitleRef.of(entry))

    /** Request, or Sign in to Requests, for a title the server doesn't have. */
    fun requestAction(
        ref: WatchlistTitleRef,
        hasSeerrSession: Boolean,
    ): WatchlistRequest {
        if (!hasSeerrSession) return WatchlistRequest.SignIn
        return ref.seerrTarget()?.let { WatchlistRequest.Seerr(it) } ?: WatchlistRequest.Unavailable
    }

    /**
     * The entries to show after removing [failed] didn't go through. [current]
     * is what shows now (other removes may have happened since); [before] is
     * the list as it was when [failed] was removed.
     */
    fun afterFailedRemove(
        current: List<WatchlistEntry>,
        failed: WatchlistEntry,
        before: List<WatchlistEntry>,
    ): List<WatchlistEntry> {
        if (current.any { it.id == failed.id }) return current
        // Back after the nearest title that was above it and still shows.
        val above = before.takeWhile { it.id != failed.id }.map { it.id }
        val anchor = current.indexOfLast { it.id in above }
        return current.toMutableList().apply { add(anchor + 1, failed) }
    }

    /**
     * The Lists page cover: the first three entries (newest first) with a
     * poster to draw, so a title not on the server and saved without one is
     * skipped (as on Apple and Roku); the app dims those not on the server.
     */
    fun cover(view: WatchlistView): List<WatchlistEntry> =
        view.entries.filter { it.itemId != null || !it.posterUrl.isNullOrBlank() }.take(PosterStack.MAX)

    /** "Use Plex too?": only before a link and until "Not now". */
    fun showsCard(view: WatchlistView): Boolean = view.plex == null && !view.cardDismissed
}
