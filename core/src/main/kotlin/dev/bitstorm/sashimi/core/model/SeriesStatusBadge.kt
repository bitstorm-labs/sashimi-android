package dev.bitstorm.sashimi.core.model

/**
 * The show-status pill on a series, season or episode detail screen. Compose-free
 * on purpose: the UI picks colours from [kind], so a future TV module can reuse it.
 */
data class SeriesStatusBadge(
    val kind: Kind,
    val label: String,
) {
    enum class Kind { CONTINUING, ENDED, UPCOMING }

    companion object {
        /**
         * The badge for a detail screen. A series uses its own `Status`; a season
         * or episode uses its parent series' [seriesStatus] (Jellyfin only sets
         * `Status` on the series). Movies and everything else get none.
         */
        fun from(
            item: BaseItemDto,
            seriesStatus: String? = null,
        ): SeriesStatusBadge? =
            when (item.type) {
                ItemType.SERIES -> forStatus(item.status)
                ItemType.SEASON, ItemType.EPISODE -> forStatus(seriesStatus)
                else -> null
            }

        /**
         * Maps Jellyfin's `BaseItemDto.Status`; a missing or unrecognised status
         * gives no badge rather than a guess.
         */
        fun forStatus(status: String?): SeriesStatusBadge? =
            when (status?.trim()?.lowercase()) {
                "continuing" -> SeriesStatusBadge(Kind.CONTINUING, "Continuing")
                "ended" -> SeriesStatusBadge(Kind.ENDED, "Ended")
                "unreleased" -> SeriesStatusBadge(Kind.UPCOMING, "Upcoming")
                else -> null
            }
    }
}
