package dev.bitstorm.sashimi.core.seerr

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The pill on a "Not in your library" poster, and the request page's state
 * before anyone presses anything. Port of sashimi-apple SeerrBadge.
 */
enum class SeerrBadge(
    val label: String,
) {
    /** Nobody has asked for it, or only some seasons are on the server. */
    REQUEST("Request"),

    /** Asked for, waiting for approval. */
    PENDING("Pending"),

    /** Approved and on its way. */
    COMING("Coming"),

    /** The person's own request was turned down. */
    DECLINED("Declined"),

    /** On the server. Search never shows these; the library row does. */
    AVAILABLE("On server"),
    ;

    companion object {
        /** [userId] is the signed-in person: only their own declined request reads "Declined". */
        fun of(
            mediaInfo: SeerrMediaInfo?,
            userId: Int?,
        ): SeerrBadge =
            when (mediaInfo?.mediaStatus) {
                SeerrMediaStatus.AVAILABLE -> AVAILABLE
                SeerrMediaStatus.PROCESSING -> COMING
                SeerrMediaStatus.PENDING -> PENDING
                SeerrMediaStatus.PARTIALLY_AVAILABLE -> REQUEST
                else -> {
                    val mine = mediaInfo?.requests.orEmpty().filter { userId == null || it.requestedBy?.id == userId }
                    val latest = mine.maxByOrNull { it.createdAt ?: "" }
                    if (latest?.requestStatus == SeerrRequestStatus.DECLINED) DECLINED else REQUEST
                }
            }
    }
}

/** A status word and the colour family it takes. */
data class SeerrStatusLabel(
    val label: String,
    val tone: SeerrBadge,
)

/** Where one request stands. Port of sashimi-apple SeerrRequestState. */
sealed interface SeerrRequestState {
    val label: String

    data object Pending : SeerrRequestState {
        override val label = "Pending"
    }

    /** Approved and downloading; [progress] (0..1) when Radarr/Sonarr report a download. */
    data class Coming(
        val progress: Double?,
    ) : SeerrRequestState {
        override val label = "Coming"
    }

    /** On the server: opens the library title. */
    data object Ready : SeerrRequestState {
        override val label = "Ready"
    }

    data object Declined : SeerrRequestState {
        override val label = "Declined"
    }

    /** Approved but sending it to Radarr/Sonarr failed. */
    data object Failed : SeerrRequestState {
        override val label = "Failed"
    }

    companion object {
        fun of(request: SeerrRequest): SeerrRequestState =
            when (request.requestStatus) {
                SeerrRequestStatus.DECLINED -> Declined
                SeerrRequestStatus.PENDING -> Pending
                SeerrRequestStatus.FAILED -> if (isOnServer(request)) Ready else Failed
                else -> if (isOnServer(request)) Ready else Coming(progress(request.media))
            }

        /** A movie is ready when it's on the server; a show when every season it asked for is. */
        private fun isOnServer(request: SeerrRequest): Boolean {
            val media = request.media ?: return false
            if (media.mediaStatus == SeerrMediaStatus.AVAILABLE) return true
            if (request.kind != SeerrMediaKind.TV || media.mediaStatus != SeerrMediaStatus.PARTIALLY_AVAILABLE) return false
            val asked = request.seasons?.map { it.seasonNumber }.orEmpty()
            if (asked.isEmpty()) return false
            val onServer =
                media.seasons
                    .orEmpty()
                    .filter { it.status == SeerrMediaStatus.AVAILABLE.raw }
                    .map { it.seasonNumber }
                    .toSet()
            return asked.all { it in onServer }
        }

        fun progress(media: SeerrMediaInfo?): Double? {
            val downloads = media?.downloadStatus.orEmpty().filter { (it.size ?: 0.0) > 0 }
            if (downloads.isEmpty()) return null
            val size = downloads.sumOf { it.size ?: 0.0 }
            val left = downloads.sumOf { minOf(it.sizeLeft ?: 0.0, it.size ?: 0.0) }
            return ((size - left) / size).coerceIn(0.0, 1.0)
        }

        /** When the title landed on the server. */
        fun arrival(request: SeerrRequest): Instant? =
            SeerrDates.parse(request.media?.mediaAddedAt) ?: SeerrDates.parse(request.media?.updatedAt)

        /** "Requested today", "Approved Tue · 62% downloaded", "Arrived yesterday". */
        fun subtitle(
            request: SeerrRequest,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String =
            when (val state = of(request)) {
                Pending, Declined -> dated("Requested", SeerrDates.parse(request.createdAt), now, zone)
                is Coming -> {
                    val approved = dated("Approved", SeerrDates.parse(request.updatedAt), now, zone)
                    state.progress?.let { "$approved · ${Math.round(it * 100)}% downloaded" } ?: approved
                }
                Ready -> dated("Arrived", arrival(request), now, zone)
                Failed -> "Approved, but it couldn't be sent for download"
            }

        /** The Home row: while something waits, or for a week after it arrives. */
        fun showsOnHome(
            request: SeerrRequest,
            now: Instant = Instant.now(),
        ): Boolean =
            when (of(request)) {
                Pending, is Coming -> true
                Ready -> arrival(request)?.let { Duration.between(it, now) < Duration.ofDays(7) } ?: false
                Declined, Failed -> false
            }

        private fun dated(
            verb: String,
            date: Instant?,
            now: Instant,
            zone: ZoneId,
        ): String = date?.let { "$verb ${dayLabel(it, now, zone)}" } ?: verb

        /** "today", "yesterday", a weekday within the week, else "Nov 2". */
        fun dayLabel(
            date: Instant,
            now: Instant,
            zone: ZoneId,
        ): String {
            val day = date.atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(day, now.atZone(zone).toLocalDate())
            return when {
                days < 1 -> "today"
                days == 1L -> "yesterday"
                days < 7 -> DateTimeFormatter.ofPattern("EEE", Locale.US).format(day)
                else -> DateTimeFormatter.ofPattern("MMM d", Locale.US).format(day)
            }
        }
    }
}
