package dev.bitstorm.sashimi.core.seerr

import kotlinx.serialization.Serializable

// The Requests page's shapes, ported from sashimi-apple
// Shared/Models/SeerrRequestManagement.swift (#845), checked against
// seerr-team/seerr 3.5.1 server/lib/permissions.ts and routes.

/** Seerr's permission bits. Only the ones the app reads are named. */
@JvmInline
value class SeerrPermissions(
    val raw: Int,
) {
    /** Seerr's `hasPermission(..., { type: 'or' })`: an administrator has every permission. */
    fun allowsAny(bits: Int): Boolean = raw and ADMIN != 0 || raw and bits != 0

    /** `GET /request` without `requestedBy` lists everyone's only for MANAGE_REQUESTS or REQUEST_VIEW. */
    val canViewAllRequests: Boolean get() = allowsAny(MANAGE_REQUESTS or REQUEST_VIEW)

    /** Approve, decline and delete any request, not just one's own pending one. */
    val canManageRequests: Boolean get() = allowsAny(MANAGE_REQUESTS)

    companion object {
        const val ADMIN = 2
        const val MANAGE_USERS = 8
        const val MANAGE_REQUESTS = 16
        const val REQUEST = 32
        const val REQUEST_VIEW = 16_384
        val NONE = SeerrPermissions(0)
    }
}

/**
 * The filter on `GET /request` (`filter=`), as the server reads it. Seerr has
 * no filter for declined requests (they only appear under All), so the list
 * offers Failed instead, as Apple and Seerr web do.
 */
enum class SeerrRequestFilter(
    val wire: String,
    val title: String,
) {
    ALL("all", "All"),
    PENDING("pending", "Pending"),
    APPROVED("approved", "Approved"),
    PROCESSING("processing", "Processing"),
    AVAILABLE("available", "Available"),
    FAILED("failed", "Failed"),
}

/** Whose requests the list shows. Everyone first: the default for those who may see them (#855). */
enum class SeerrRequestScope(
    val title: String,
) {
    EVERYONE("Everyone"),
    MINE("Mine"),
}

/** `GET /request/count`: every request on the server, whoever asked. */
@Serializable
data class SeerrRequestCount(
    val total: Int? = null,
    val movie: Int? = null,
    val tv: Int? = null,
    val pending: Int? = null,
    val approved: Int? = null,
    val declined: Int? = null,
    val processing: Int? = null,
    val available: Int? = null,
    val completed: Int? = null,
) {
    /** "493 requests · 0 pending · 78 processing · 56 available". */
    val summary: String
        get() {
            val parts = mutableListOf("${total ?: 0} requests", "${pending ?: 0} pending")
            processing?.let { parts += "$it processing" }
            available?.let { parts += "$it available" }
            return parts.joinToString(" · ")
        }
}

/** One half of `GET /user/{id}/quota`. A `limit` of 0 (or none) is unlimited; TV quotas count seasons. */
@Serializable
data class SeerrQuotaStatus(
    val days: Int? = null,
    val limit: Int? = null,
    val used: Int? = null,
    val remaining: Int? = null,
    val restricted: Boolean? = null,
)

@Serializable
data class SeerrQuota(
    val movie: SeerrQuotaStatus? = null,
    val tv: SeerrQuotaStatus? = null,
) {
    /** The line under the Request button; null without a limit. */
    fun summary(kind: SeerrMediaKind): String? {
        val status = (if (kind == SeerrMediaKind.MOVIE) movie else tv) ?: return null
        val limit = status.limit?.takeIf { it > 0 } ?: return null
        val noun = if (kind == SeerrMediaKind.MOVIE) "movie" else "season"
        val left = maxOf(0, status.remaining ?: (limit - (status.used ?: 0)))
        val window =
            status.days?.let { days -> if (days > 0) " · resets over $days day${if (days == 1) "" else "s"}" else "" } ?: ""
        if (status.restricted == true || left == 0) return "You've used all $limit of your $noun requests$window"
        return "$left of $limit $noun requests left$window"
    }
}

/** The Discover page's rows, in Seerr web's order. */
enum class SeerrDiscoverSlider(
    val title: String,
    /** The `/api/v1/` path; null for Recent Requests, which is `GET /request`. */
    val path: String?,
) {
    RECENT_REQUESTS("Recent Requests", null),
    TRENDING("Trending", "discover/trending"),
    POPULAR_MOVIES("Popular Movies", "discover/movies"),
    UPCOMING_MOVIES("Upcoming Movies", "discover/movies/upcoming"),
    POPULAR_SERIES("Popular Series", "discover/tv"),
    UPCOMING_SERIES("Upcoming Series", "discover/tv/upcoming"),

    /** The Plex Watchlist; empty (and hidden) for local accounts without one. */
    WATCHLIST("Your Watchlist", "discover/watchlist"),
}
