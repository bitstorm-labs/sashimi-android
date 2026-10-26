package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary

/**
 * What the side menu (the nav rail on wide layouts) lists, in order (#187,
 * parity with sashimi-apple RailOrder): Home, then each library in the Home
 * row order, then Search, Downloads and Settings. Pure, so the rule is
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
     * [libraries] (what the server reports now, in its order).
     *
     * - Libraries follow the row order; one the rows don't mention yet (added
     *   on the server since) follows in server order, shown.
     * - A row switched off for the side menu is left out. Its Home toggle has
     *   no say: hiding a row on Home is a statement about Home.
     * - A row naming a library the server no longer has is ignored.
     * - [online] false (the offline library) lists Home, Downloads, Settings:
     *   nothing else works without the server.
     */
    fun items(
        rows: List<HomeRowConfig>,
        libraries: List<JellyfinLibrary>,
        online: Boolean = true,
    ): List<SideMenuItem> {
        if (!online) return listOf(SideMenuItem.Home, SideMenuItem.Downloads, SideMenuItem.Settings)
        val byId = libraries.associateBy { it.id }
        val placed = mutableSetOf<String>()
        val shown = mutableListOf<SideMenuItem.Library>()
        for (row in rows) {
            if (!row.isSideMenuDestination) continue
            val library = byId[row.libraryId] ?: continue
            if (!placed.add(library.id)) continue
            if (row.showInSideMenu) shown += library.asItem()
        }
        for (library in libraries) {
            if (placed.add(library.id)) shown += library.asItem()
        }
        return listOf(SideMenuItem.Home) + shown + listOf(SideMenuItem.Search, SideMenuItem.Downloads, SideMenuItem.Settings)
    }

    /**
     * The libraries that get a Home row, and so a side-menu entry: media
     * libraries (no playlists, books or channels). Untyped "mixed" folders count.
     */
    fun isMediaLibrary(collectionType: String?): Boolean {
        val type = collectionType?.lowercase() ?: return true
        return type in setOf("movies", "tvshows", "music", "mixed", "homevideos")
    }

    private fun JellyfinLibrary.asItem() = SideMenuItem.Library(id, name, collectionType)
}
