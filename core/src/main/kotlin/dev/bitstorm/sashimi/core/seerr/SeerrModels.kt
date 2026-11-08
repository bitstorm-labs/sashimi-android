package dev.bitstorm.sashimi.core.seerr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// Seerr (Overseerr/Jellyseerr) API shapes, ported from sashimi-apple
// Shared/Models/SeerrModels.swift and checked against seerr-team/seerr 3.5.x.
// Every field the app does not strictly need is optional, so one odd search
// result can't fail a whole page. Secrets the server can return on a user
// (plexToken, jellyfinAuthToken) are deliberately not decoded.

/** Movie or show. Seerr's search also returns people; those are dropped. */
enum class SeerrMediaKind(
    val wire: String,
) {
    MOVIE("movie"),
    TV("tv"),
    ;

    companion object {
        fun from(wire: String?): SeerrMediaKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** `MediaStatus` on the server: whether a title (or season) is there, coming, or nowhere. */
enum class SeerrMediaStatus(
    val raw: Int,
) {
    UNKNOWN(1),
    PENDING(2),
    PROCESSING(3),
    PARTIALLY_AVAILABLE(4),
    AVAILABLE(5),
    BLOCKLISTED(6),
    DELETED(7),
    ;

    companion object {
        fun from(raw: Int?): SeerrMediaStatus? = entries.firstOrNull { it.raw == raw }
    }
}

/** `MediaRequestStatus` on the server. */
enum class SeerrRequestStatus(
    val raw: Int,
) {
    PENDING(1),
    APPROVED(2),
    DECLINED(3),
    FAILED(4),
    COMPLETED(5),
    ;

    companion object {
        fun from(raw: Int?): SeerrRequestStatus? = entries.firstOrNull { it.raw == raw }
    }
}

@Serializable
data class SeerrUser(
    val id: Int,
    val displayName: String? = null,
    val username: String? = null,
    val plexUsername: String? = null,
    val email: String? = null,
    /** Seerr's permission bits ([SeerrPermissions]). Null when restored offline before it was saved. */
    val permissions: Int? = null,
) {
    /** What "Signed in as ..." shows. */
    val name: String
        get() = listOf(displayName, username, plexUsername, email).firstOrNull { !it.isNullOrBlank() } ?: "Seerr user"

    val permissionSet: SeerrPermissions get() = SeerrPermissions(permissions ?: 0)
}

/** `GET /api/v1/settings/public`: needs no sign-in. */
@Serializable
data class SeerrPublicSettings(
    val applicationTitle: String? = null,
    val localLogin: Boolean? = null,
    val partialRequestsEnabled: Boolean? = null,
)

@Serializable
data class SeerrSeasonStatus(
    val seasonNumber: Int,
    val status: Int? = null,
)

@Serializable
data class SeerrSeasonRequest(
    val seasonNumber: Int,
    val status: Int? = null,
)

/** One download Seerr sees in Radarr/Sonarr for the title. */
@Serializable
data class SeerrDownload(
    val size: Double? = null,
    val sizeLeft: Double? = null,
)

/** `MediaInfo`: the server's record of a title. Absent when nobody ever asked and it was never seen. */
@Serializable
data class SeerrMediaInfo(
    val id: Int? = null,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val status: Int? = null,
    val seasons: List<SeerrSeasonStatus>? = null,
    val requests: List<SeerrRequest>? = null,
    val downloadStatus: List<SeerrDownload>? = null,
    val mediaAddedAt: String? = null,
    val updatedAt: String? = null,
) {
    val mediaStatus: SeerrMediaStatus? get() = SeerrMediaStatus.from(status)
    val kind: SeerrMediaKind? get() = SeerrMediaKind.from(mediaType)
}

/** A request's requester. Seerr fills `displayName` itself. */
@Serializable
data class SeerrRequestUser(
    val id: Int,
    val displayName: String? = null,
    val username: String? = null,
    val plexUsername: String? = null,
    /** A full URL (Plex, Gravatar) or a server path ("/avatarproxy/..."). */
    val avatar: String? = null,
) {
    val name: String
        get() = listOf(displayName, username, plexUsername).firstOrNull { !it.isNullOrBlank() } ?: "User $id"

    /** The avatar, resolved against the Seerr server for a server path. */
    fun avatarUrl(server: String): String? {
        val avatar = avatar?.takeIf { it.isNotEmpty() } ?: return null
        if (avatar.startsWith("http://") || avatar.startsWith("https://")) return avatar
        if (!avatar.startsWith("/")) return null
        return server.trimEnd('/') + avatar
    }
}

/** `MediaRequest`. */
@Serializable
data class SeerrRequest(
    val id: Int,
    val status: Int,
    val type: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val seasons: List<SeerrSeasonRequest>? = null,
    val media: SeerrMediaInfo? = null,
    val requestedBy: SeerrRequestUser? = null,
    val modifiedBy: SeerrRequestUser? = null,
) {
    val requestStatus: SeerrRequestStatus? get() = SeerrRequestStatus.from(status)
    val kind: SeerrMediaKind? get() = SeerrMediaKind.from(type)
}

/** `pageInfo` on `GET /request`: `results` is the total across pages. */
@Serializable
data class SeerrPageInfo(
    val pages: Int? = null,
    val pageSize: Int? = null,
    val results: Int? = null,
    val page: Int? = null,
)

@Serializable
data class SeerrRequestPage(
    val results: List<SeerrRequest>,
    val pageInfo: SeerrPageInfo? = null,
)

/** One search hit: only movies and shows. */
data class SeerrSearchResult(
    val tmdbId: Int,
    val kind: SeerrMediaKind,
    val title: String,
    val date: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val mediaInfo: SeerrMediaInfo? = null,
) {
    val id: String get() = "${kind.wire}-$tmdbId"
    val year: Int? get() = SeerrDates.year(date)

    companion object {
        /**
         * Null for anything that isn't a movie or show. The watchlist names the
         * TMDB id `tmdbId` (for a local account its `id` is the entry's own);
         * everything else only has `id`.
         */
        fun from(element: JsonElement): SeerrSearchResult? {
            val obj = element as? JsonObject ?: return null
            val kind = SeerrMediaKind.from(obj.string("mediaType")) ?: return null
            val tmdbId = obj.int("tmdbId") ?: obj.int("id") ?: return null
            return SeerrSearchResult(
                tmdbId = tmdbId,
                kind = kind,
                title = obj.string("title") ?: obj.string("name") ?: "",
                date = obj.string("releaseDate") ?: obj.string("firstAirDate"),
                posterPath = obj.string("posterPath"),
                backdropPath = obj.string("backdropPath"),
                overview = obj.string("overview"),
                mediaInfo = obj["mediaInfo"]?.let { SeerrJson.decodeOrNull<SeerrMediaInfo>(it) },
            )
        }
    }
}

data class SeerrSearchPage(
    val page: Int,
    val totalPages: Int,
    val results: List<SeerrSearchResult>,
) {
    companion object {
        /** Skips entries that aren't a movie or show instead of failing the page. */
        fun parse(text: String): SeerrSearchPage {
            val obj = SeerrJson.json.parseToJsonElement(text).jsonObject
            return SeerrSearchPage(
                page = obj.int("page") ?: 1,
                totalPages = obj.int("totalPages") ?: 1,
                results = (obj["results"] as? JsonArray).orEmpty().mapNotNull(SeerrSearchResult::from),
            )
        }
    }
}

// MARK: - Details

@Serializable
data class SeerrCastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    val profilePath: String? = null,
)

/** A show's season as TMDB lists it. */
@Serializable
data class SeerrSeason(
    val seasonNumber: Int,
    val episodeCount: Int? = null,
    val airDate: String? = null,
    val name: String? = null,
)

/**
 * `GET /api/v1/movie/{id}` or `/tv/{id}` as one shape: the two differ only in
 * field names and the parts only a show has.
 */
data class SeerrTitleDetails(
    val tmdbId: Int,
    val kind: SeerrMediaKind,
    val title: String,
    val date: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val genres: List<String> = emptyList(),
    /** First YouTube trailer (or teaser) key. */
    val trailerKey: String? = null,
    val cast: List<SeerrCastMember> = emptyList(),
    val mediaInfo: SeerrMediaInfo? = null,
    /** TMDB's show status ("Returning Series", "Ended", ...); null on a movie. */
    val tmdbStatus: String? = null,
    val seasons: List<SeerrSeason> = emptyList(),
    /** TV: content rating; movies: certification. US first, else the first non-empty. */
    val contentRating: String? = null,
) {
    val year: Int? get() = SeerrDates.year(date)

    /** "Continuing" / "Ended" / "Upcoming" for a show, from TMDB's words. */
    val seriesStatus: String? get() = if (kind == SeerrMediaKind.TV) seriesStatusFor(tmdbStatus) else null

    companion object {
        fun seriesStatusFor(tmdbStatus: String?): String? =
            when (tmdbStatus?.lowercase()) {
                "returning series", "in production" -> "Continuing"
                "ended", "canceled", "cancelled" -> "Ended"
                "planned", "pilot" -> "Upcoming"
                else -> null
            }

        fun parse(text: String): SeerrTitleDetails {
            val obj = SeerrJson.json.parseToJsonElement(text).jsonObject
            val movieTitle = obj.string("title")
            val videos = (obj["relatedVideos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val youTube = videos.filter { it.string("site") == "YouTube" && it.string("key") != null }
            val trailer = (youTube.firstOrNull { it.string("type") == "Trailer" } ?: youTube.firstOrNull { it.string("type") == "Teaser" })
            return SeerrTitleDetails(
                tmdbId = obj.int("id") ?: throw IllegalArgumentException("no id"),
                kind = if (movieTitle == null) SeerrMediaKind.TV else SeerrMediaKind.MOVIE,
                title = movieTitle ?: obj.string("name") ?: "",
                date = obj.string("releaseDate") ?: obj.string("firstAirDate"),
                overview = obj.string("overview"),
                posterPath = obj.string("posterPath"),
                backdropPath = obj.string("backdropPath"),
                genres = (obj["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") },
                trailerKey = trailer?.string("key"),
                cast =
                    ((obj["credits"] as? JsonObject)?.get("cast") as? JsonArray).orEmpty().mapNotNull {
                        SeerrJson.decodeOrNull<SeerrCastMember>(it)
                    },
                mediaInfo = obj["mediaInfo"]?.let { SeerrJson.decodeOrNull<SeerrMediaInfo>(it) },
                tmdbStatus = obj.string("status"),
                seasons = (obj["seasons"] as? JsonArray).orEmpty().mapNotNull { SeerrJson.decodeOrNull<SeerrSeason>(it) },
                contentRating = rating(obj["contentRatings"] as? JsonObject, obj["releases"] as? JsonObject),
            )
        }

        private fun rating(
            tv: JsonObject?,
            movie: JsonObject?,
        ): String? {
            (tv?.get("results") as? JsonArray)?.let { results ->
                val rated = results.mapNotNull { it as? JsonObject }.filter { !it.string("rating").isNullOrEmpty() }
                return (rated.firstOrNull { it.string("iso_3166_1") == "US" } ?: rated.firstOrNull())?.string("rating")
            }
            val countries = (movie?.get("results") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null

            fun certification(country: JsonObject): String? =
                (country["release_dates"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("certification") }
                    .firstOrNull { it.isNotEmpty() }
            countries.firstOrNull { it.string("iso_3166_1") == "US" }?.let(::certification)?.let { return it }
            return countries.firstNotNullOfOrNull(::certification)
        }
    }
}

// MARK: - Plex PIN

/** `POST https://plex.tv/api/v2/pins` / `GET .../pins/{id}`. `authToken` is set once linked. */
@Serializable
data class PlexPin(
    val id: Int,
    val code: String,
    val authToken: String? = null,
    val expiresAt: String? = null,
)

// MARK: - Helpers

object SeerrDates {
    /** "2024-11-17" to 2024. */
    fun year(date: String?): Int? = date?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()

    /** ISO 8601 with or without fractional seconds, or a bare TMDB "yyyy-MM-dd" (midnight UTC). */
    fun parse(text: String?): Instant? {
        if (text.isNullOrEmpty()) return null
        try {
            return Instant.parse(text)
        } catch (_: DateTimeParseException) {
        }
        return try {
            LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

object SeerrImages {
    /** TMDB artwork by path ("/abc.jpg"), from TMDB's CDN like the Seerr website. */
    fun poster(
        path: String?,
        width: Int = 500,
    ): String? = url(path, "w$width")

    fun backdrop(path: String?): String? = url(path, "w1280")

    fun profile(path: String?): String? = url(path, "w185")

    private fun url(
        path: String?,
        size: String,
    ): String? = if (path != null && path.startsWith("/")) "https://image.tmdb.org/t/p/$size$path" else null
}

internal object SeerrJson {
    val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

    inline fun <reified T> decodeOrNull(element: JsonElement): T? = runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

@Serializable
internal data class SeerrRequestBody(
    val mediaType: String,
    val mediaId: Int,
    val seasons: List<Int>? = null,
)

@Serializable
internal data class SeerrErrorBody(
    val message: String? = null,
    @SerialName("error") val error: String? = null,
)
