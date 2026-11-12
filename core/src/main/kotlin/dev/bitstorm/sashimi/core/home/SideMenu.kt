package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary

/**
 * What the side menu (the nav rail on wide layouts) lists, in order (#187,
 * parity with sashimi-apple RailOrder):
 * Home, Search, then each destination in the Home row order (the Watchlist
 * first by default), then Downloads and Settings. Pure, so the rule is
 * unit-tested; the rail is only a view of it.
 */
sealed interface SideMenuItem {
    /** Stable identity, for selection and list keys. */
    val key: String

    data object Home : SideMenuItem {
        override val key = "home"
    }

    data class Library(
        val id: String,
        val name: String,
        val collectionType: String?,
    ) : SideMenuItem {
        override val key = "library:$id"
    }

    /** Shared lists (2026-10-27): yours and everyone's public ones. */
    data object Lists : SideMenuItem {
        override val key = "lists"
    }

    /** Seerr requests: while a Seerr account is connected (or its sign-in expired). */
    data object Requests : SideMenuItem {
        override val key = "requests"
    }

    /** The admin server dashboard: administrators only. */
    data object Dashboard : SideMenuItem {
        override val key = "dashboard"
    }

    /** The signed-in user's Watchlist, opening its Spotlight page (owner, 2026-11-11). */
    data object Watchlist : SideMenuItem {
        override val key = "watchlist"
    }

    data object Search : SideMenuItem {
        override val key = "search"
    }

    data object Downloads : SideMenuItem {
        override val key = "downloads"
    }

    data object Settings : SideMenuItem {
        override val key = "settings"
    }
}

object SideMenu {
    /** Always in the menu; there is no way to hide them. */
    val ALWAYS_SHOWN: List<SideMenuItem> = listOf(SideMenuItem.Home, SideMenuItem.Search, SideMenuItem.Downloads, SideMenuItem.Settings)

    /** The settings footnote listing [ALWAYS_SHOWN]. */
    const val ALWAYS_SHOWN_NOTE = "Always shown: Home, Search, Downloads, Settings"

    const val ONLY_FROM_SEARCH_NOTE = "Only reachable from Search"

    /**
     * The menu for [rows] (the user's Home row order and toggles) and
     * [libraries] (what the server reports now, in its order). Every
     * destination after Home and Search is listed in exactly the rows'
     * relative order (2026-11-07 parity brief, A), then Downloads and
     * Settings. Search sits right under Home, as on the Apple rail, so the
     * Watchlist's default first place puts it directly under Search.
     *
     * - A library row is listed where it sits; one the rows don't mention yet
     *   (added on the server since) follows the last listed library, shown.
     * - The requests row carries the Requests entry ([showRequests]: a Seerr
     *   account is connected or expired). Lists ([showLists]) and the
     *   Dashboard ([showDashboard]: administrators) are menu-only entries in
     *   the same list.
     * - A row switched off for the side menu is left out. Its Home toggle has
     *   no say: hiding a row on Home is a statement about Home. Lists keeps its
     *   own switch, [showLists], which also drives the phone tab bar.
     * - A row naming a library the server no longer has is ignored.
     * - The Watchlist row carries the Watchlist entry; its side-menu toggle
     *   hides it ([showsWatchlist] also drives the phone tab bar).
     * - An entry the rows don't place (an order saved before it existed and
     *   never normalised) trails, Lists then Requests then Dashboard; an
     *   unplaced Watchlist leads, where a normalised order puts it.
     * - [online] false (the offline library) lists Home, Downloads, Settings:
     *   nothing else works without the server.
     */
    fun items(
        rows: List<HomeRowConfig>,
        libraries: List<JellyfinLibrary>,
        online: Boolean = true,
        showLists: Boolean = false,
        showRequests: Boolean = false,
        showDashboard: Boolean = false,
    ): List<SideMenuItem> {
        if (!online) return listOf(SideMenuItem.Home, SideMenuItem.Downloads, SideMenuItem.Settings)
        val byId = libraries.associateBy { it.id }
        val placedLibraries = mutableSetOf<String>()
        val placedKinds = mutableSetOf<HomeRowConfig.Kind>()
        val shown = mutableListOf<SideMenuItem>()
        var afterLastLibrary = 0
        for (row in rows) {
            when (row.kind) {
                HomeRowConfig.Kind.LIBRARY -> {
                    val library = byId[row.libraryId] ?: continue
                    if (!placedLibraries.add(library.id)) continue
                    if (row.showInSideMenu) shown += library.asItem()
                    afterLastLibrary = shown.size
                }
                HomeRowConfig.Kind.REQUESTS ->
                    if (placedKinds.add(row.kind) && showRequests && row.showInSideMenu) shown += SideMenuItem.Requests
                HomeRowConfig.Kind.LISTS ->
                    if (placedKinds.add(row.kind) && showLists) shown += SideMenuItem.Lists
                HomeRowConfig.Kind.DASHBOARD ->
                    if (placedKinds.add(row.kind) && showDashboard && row.showInSideMenu) shown += SideMenuItem.Dashboard
                HomeRowConfig.Kind.WATCHLIST ->
                    if (placedKinds.add(row.kind) && row.showInSideMenu) shown += SideMenuItem.Watchlist
                HomeRowConfig.Kind.CONTINUE_WATCHING, HomeRowConfig.Kind.LIST -> Unit
            }
        }
        val added = libraries.filter { placedLibraries.add(it.id) }.map { it.asItem() }
        shown.addAll(afterLastLibrary, added)
        if (HomeRowConfig.Kind.LISTS !in placedKinds && showLists) shown += SideMenuItem.Lists
        if (HomeRowConfig.Kind.REQUESTS !in placedKinds && showRequests) shown += SideMenuItem.Requests
        if (HomeRowConfig.Kind.DASHBOARD !in placedKinds && showDashboard) shown += SideMenuItem.Dashboard
        if (HomeRowConfig.Kind.WATCHLIST !in placedKinds) shown.add(0, SideMenuItem.Watchlist)
        return listOf(SideMenuItem.Home, SideMenuItem.Search) + shown + listOf(SideMenuItem.Downloads, SideMenuItem.Settings)
    }

    /**
     * The libraries that get a Home row, and so a side-menu entry: media
     * libraries (no playlists, books or channels). Untyped "mixed" folders count.
     */
    fun isMediaLibrary(collectionType: String?): Boolean {
        val type = collectionType?.lowercase() ?: return true
        return type in setOf("movies", "tvshows", "music", "mixed", "homevideos")
    }

    /** The Watchlist row's side-menu switch; shown when the rows don't mention it yet. */
    fun showsWatchlist(rows: List<HomeRowConfig>): Boolean =
        rows.firstOrNull { it.kind == HomeRowConfig.Kind.WATCHLIST }?.showInSideMenu ?: true

    private fun JellyfinLibrary.asItem() = SideMenuItem.Library(id, name, collectionType)
}
