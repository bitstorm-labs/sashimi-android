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

/** What tapping a Watchlist tile does. */
sealed interface WatchlistTap {
    /** On the server: its detail page. */
    data class Open(
        val itemId: String,
    ) : WatchlistTap

    /** Not on the server: its Seerr page, with Request. */
    data class Seerr(
        val target: SeerrTarget,
    ) : WatchlistTap

    /** Signed into Requests, but there's no TMDB id to open a Seerr page with. */
    data object NotOnServer : WatchlistTap

    /** Not on the server and no Requests sign-in on this device. */
    data object SignInToRequest : WatchlistTap
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
    fun seerrTarget(entry: WatchlistEntry): SeerrTarget? {
        val tmdb = entry.tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return SeerrTarget(if (entry.isSeries) SeerrMediaKind.TV else SeerrMediaKind.MOVIE, tmdb, entry.title)
    }

    /**
     * Whether Add to List offers the Watchlist. [target] is the library item,
     * or null for a Seerr title (always a movie or show with a TMDB id). A null
     * [view] means no plugin, or it couldn't be read: no row then.
     */
    fun pickerShowsWatchlist(
        target: BaseItemDto?,
        view: WatchlistView?,
    ): Boolean = view != null && (target == null || eligible(target))

    /** A Seerr title isn't a library item, so only the Watchlist can take it. */
    fun pickerShowsLists(isSeerr: Boolean): Boolean = !isSeerr

    fun tapAction(
        entry: WatchlistEntry,
        hasSeerrSession: Boolean,
    ): WatchlistTap {
        entry.itemId?.let { return WatchlistTap.Open(it) }
        if (!hasSeerrSession) return WatchlistTap.SignInToRequest
        return seerrTarget(entry)?.let { WatchlistTap.Seerr(it) } ?: WatchlistTap.NotOnServer
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
    ): List<WatchlistEntry> = before

    /** "Use Plex too?": only before a link and until "Not now". */
    fun showsCard(view: WatchlistView): Boolean = view.plex == null && !view.cardDismissed
}
