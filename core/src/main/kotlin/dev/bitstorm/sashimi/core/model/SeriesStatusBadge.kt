package dev.bitstorm.sashimi.core.model

/**
 * The show-status pill on a series detail screen. Compose-free on purpose: the UI
 * picks colours from [kind], so a future TV module can reuse the mapping.
 */
data class SeriesStatusBadge(
    val kind: Kind,
    val label: String,
) {
    enum class Kind { CONTINUING, ENDED, UPCOMING }

    companion object {
        /**
         * Maps Jellyfin's `BaseItemDto.Status` to a badge. Series only: seasons,
         * episodes and movies never get one, and a missing or unrecognised status
         * gives no badge rather than a guess.
         */
        fun from(item: BaseItemDto): SeriesStatusBadge? {
            if (item.type != ItemType.SERIES) return null
            return when (item.status?.trim()?.lowercase()) {
                "continuing" -> SeriesStatusBadge(Kind.CONTINUING, "Continuing")
                "ended" -> SeriesStatusBadge(Kind.ENDED, "Ended")
                "unreleased" -> SeriesStatusBadge(Kind.UPCOMING, "Upcoming")
                else -> null
            }
        }
    }
}
