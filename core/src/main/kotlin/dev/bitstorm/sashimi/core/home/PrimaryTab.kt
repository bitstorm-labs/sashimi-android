package dev.bitstorm.sashimi.core.home

/**
 * The phone's primary navigation (the bottom bar), in order. Lists and the
 * Watchlist follow their side-menu switches; the Watchlist sits right after
 * Search (owner, 2026-11-11). Offline keeps only what works without the
 * server (iPhone PhoneTabView parity).
 */
enum class PrimaryTab {
    HOME,
    LIBRARIES,
    LISTS,
    SEARCH,
    WATCHLIST,
    DOWNLOADS,
    SETTINGS,
    ;

    companion object {
        fun phoneTabs(
            online: Boolean,
            showLists: Boolean,
            showWatchlist: Boolean,
        ): List<PrimaryTab> =
            if (!online) {
                listOf(HOME, DOWNLOADS, SETTINGS)
            } else {
                entries.filter { (it != LISTS || showLists) && (it != WATCHLIST || showWatchlist) }
            }
    }
}
