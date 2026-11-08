package dev.bitstorm.sashimi.core.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneId

/** What a request page is opened for. */
data class SeerrTarget(
    val kind: SeerrMediaKind,
    val tmdbId: Int,
    val title: String,
    val posterPath: String? = null,
) {
    val id: String get() = "${kind.wire}-$tmdbId"

    companion object {
        fun of(result: SeerrSearchResult) = SeerrTarget(result.kind, result.tmdbId, result.title, result.posterPath)
    }
}

/** A Seerr title on a poster: the result and its pill. */
data class SeerrSearchItem(
    val result: SeerrSearchResult,
    val badge: SeerrBadge,
) {
    val id: String get() = result.id
    val target: SeerrTarget get() = SeerrTarget.of(result)

    /** The poster's pill on Discover, in Seerr web's words: nothing for a title nobody has asked for. */
    val discoverTag: SeerrStatusLabel?
        get() =
            when (result.mediaInfo?.mediaStatus) {
                SeerrMediaStatus.AVAILABLE -> SeerrStatusLabel("Available", SeerrBadge.AVAILABLE)
                SeerrMediaStatus.PARTIALLY_AVAILABLE -> SeerrStatusLabel("Partially Available", SeerrBadge.COMING)
                SeerrMediaStatus.PROCESSING -> SeerrStatusLabel("Processing", SeerrBadge.COMING)
                SeerrMediaStatus.PENDING -> SeerrStatusLabel("Requested", SeerrBadge.PENDING)
                else -> if (badge == SeerrBadge.DECLINED) SeerrStatusLabel("Declined", SeerrBadge.DECLINED) else null
            }
}

/**
 * Titles, posters and media state for TMDB ids, from Seerr's details route.
 * Seerr's request list and the Plex watchlist carry only the id, so the
 * screens fill in what's missing here, once per title.
 */
class SeerrTitleCache {
    data class Entry(
        val title: String,
        val posterPath: String?,
        val mediaInfo: SeerrMediaInfo?,
    )

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun entry(target: SeerrTarget): Entry? = entries[target.id]

    suspend fun fill(
        requests: List<SeerrRequest>,
        client: SeerrClient,
    ) = fillTargets(requests.mapNotNull(::target), client)

    /** Fetches every target not already known, in parallel. A title that fails to load is left out. */
    suspend fun fillTargets(
        targets: List<SeerrTarget>,
        client: SeerrClient,
    ) {
        val missing = targets.map { SeerrTarget(it.kind, it.tmdbId, "") }.distinct().filter { entries[it.id] == null }
        if (missing.isEmpty()) return
        coroutineScope {
            missing
                .map { target ->
                    async {
                        try {
                            val details = client.details(target.kind, target.tmdbId)
                            target.id to Entry(details.title, details.posterPath, details.mediaInfo)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                .filterNotNull()
                .forEach { (id, entry) -> entries[id] = entry }
        }
    }

    companion object {
        /** The request's title as a target (title still empty). */
        fun target(request: SeerrRequest): SeerrTarget? {
            val tmdbId = request.media?.tmdbId ?: return null
            val kind = request.kind ?: request.media.kind ?: return null
            return SeerrTarget(kind, tmdbId, "")
        }
    }
}

/** One request, with the title Seerr's request list leaves out. */
data class SeerrRequestRow(
    val id: Int,
    val request: SeerrRequest,
    val kind: SeerrMediaKind,
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val state: SeerrRequestState,
    val subtitle: String,
) {
    val target: SeerrTarget get() = SeerrTarget(kind, tmdbId, title, posterPath)

    /** Who asked: their Seerr display name. */
    val requesterName: String get() = request.requestedBy?.name ?: "Unknown"

    private val seasonNumbers: List<Int> get() =
        request.seasons
            .orEmpty()
            .map { it.seasonNumber }
            .sorted()

    /** "Dune: Prophecy · S1" for a show request. */
    val displayTitle: String
        get() {
            val seasons = seasonNumbers
            if (kind != SeerrMediaKind.TV || seasons.isEmpty()) return title
            return "$title · ${seasonSpan(seasons)}"
        }

    /** "Movie", "Series · S1–3". */
    val kindLabel: String
        get() {
            if (kind != SeerrMediaKind.TV) return "Movie"
            val seasons = seasonNumbers
            return if (seasons.isEmpty()) "Series" else "Series · ${seasonSpan(seasons)}"
        }

    /** The request's own state, as Seerr's request list words it. */
    val requestStatus: SeerrStatusLabel
        get() =
            when (request.requestStatus) {
                SeerrRequestStatus.PENDING -> SeerrStatusLabel("Pending", SeerrBadge.PENDING)
                SeerrRequestStatus.APPROVED -> SeerrStatusLabel("Approved", SeerrBadge.COMING)
                SeerrRequestStatus.DECLINED -> SeerrStatusLabel("Declined", SeerrBadge.DECLINED)
                SeerrRequestStatus.FAILED -> SeerrStatusLabel("Failed", SeerrBadge.DECLINED)
                SeerrRequestStatus.COMPLETED -> SeerrStatusLabel("Completed", SeerrBadge.AVAILABLE)
                null -> SeerrStatusLabel("Unknown", SeerrBadge.REQUEST)
            }

    /** Where the title itself stands; null when that adds nothing. */
    val mediaStatus: SeerrStatusLabel?
        get() =
            when (request.media?.mediaStatus) {
                SeerrMediaStatus.AVAILABLE -> SeerrStatusLabel("Available", SeerrBadge.AVAILABLE)
                SeerrMediaStatus.PARTIALLY_AVAILABLE -> SeerrStatusLabel("Partially Available", SeerrBadge.COMING)
                SeerrMediaStatus.PROCESSING -> SeerrStatusLabel("Processing", SeerrBadge.COMING)
                SeerrMediaStatus.DELETED -> SeerrStatusLabel("Deleted", SeerrBadge.DECLINED)
                SeerrMediaStatus.BLOCKLISTED -> SeerrStatusLabel("Blocklisted", SeerrBadge.DECLINED)
                else -> null
            }

    /** Download progress (0..1) when Radarr/Sonarr report one. */
    val progress: Double? get() = SeerrRequestState.progress(request.media)

    /** "Requested Tue" / "Requested Nov 2". */
    fun requestedLabel(
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = SeerrDates.parse(request.createdAt) ?: return "Requested"
        return "Requested ${SeerrRequestState.dayLabel(date, now, zone)}"
    }

    /** The same row with the request moved to [status] (an approve or decline, before the reload lands). */
    fun withStatus(status: SeerrRequestStatus): SeerrRequestRow {
        val updated = request.copy(status = status.raw)
        return copy(request = updated, state = SeerrRequestState.of(updated), subtitle = SeerrRequestState.subtitle(updated))
    }

    companion object {
        /** Null when the request names no title (Seerr can return one whose media row is gone). */
        fun of(
            request: SeerrRequest,
            titles: SeerrTitleCache,
            now: Instant = Instant.now(),
        ): SeerrRequestRow? {
            val target = SeerrTitleCache.target(request) ?: return null
            val known = titles.entry(target)
            return SeerrRequestRow(
                id = request.id,
                request = request,
                kind = target.kind,
                tmdbId = target.tmdbId,
                title = known?.title ?: "Unknown title",
                posterPath = known?.posterPath,
                state = SeerrRequestState.of(request),
                subtitle = SeerrRequestState.subtitle(request, now),
            )
        }

        private fun seasonSpan(seasons: List<Int>): String {
            val first = seasons.first()
            val last = seasons.last()
            return when {
                seasons.size == 1 -> "S$first"
                last - first + 1 == seasons.size -> "S$first–$last"
                else -> "${seasons.size} seasons"
            }
        }
    }
}

/** What the person may do to one request in the list. */
enum class SeerrRequestAction(
    val title: String,
) {
    APPROVE("Approve"),
    DECLINE("Decline"),

    /** The requester's own pending request. */
    CANCEL("Cancel Request"),

    /** MANAGE_REQUESTS: any request, whoever made it. */
    DELETE("Delete Request"),
    ;

    val isDestructive: Boolean get() = this != APPROVE
}
