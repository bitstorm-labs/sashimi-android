package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.lists.SharedLists
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Configuration for a single Home tab row — order + visibility, persisted to a
 * [HomeRowStore]. Port of Settings/HomeRowSettings.swift (HomeRowConfig).
 *
 * The Swift model used a nested enum (`.builtIn` / `.library`); this is
 * flattened to a single Codable-friendly shape ([kind] + optional library
 * fields) so it serializes to stable JSON without a polymorphic serializer.
 * There is one built-in row today — Continue Watching — plus one row per
 * library (Recently Added <library>).
 */
@Serializable
data class HomeRowConfig(
    val kind: Kind,
    val libraryId: String? = null,
    val libraryName: String? = null,
    val isEnabled: Boolean = true,
    /**
     * Listed in the side menu (the nav rail on wide layouts, #187). Only a
     * row with [isSideMenuDestination] uses it; independent of [isEnabled].
     * Defaults to shown, so saved rows from before the setting existed and
     * libraries added later are in the menu.
     */
    val showInSideMenu: Boolean = true,
    /** A subscribed list's playlist id ([Kind.LIST] only). */
    val listId: String? = null,
    /** The list's row title as last loaded ("From Marge: Christmas Movies"). */
    val listTitle: String? = null,
) {
    /** Whether this row has a side-menu toggle at all ("—" otherwise). */
    val isSideMenuDestination: Boolean
        get() = kind == Kind.LIBRARY || kind == Kind.REQUESTS || isMenuOnly

    /**
     * A side-menu entry Home draws no row for (Search, the Watchlist, Lists,
     * the admin Dashboard, Downloads): it is in this list only for its place in
     * the one order Home & Side Menu edits, and its Home toggle is "—" (Apple
     * SideMenuOrder parity).
     */
    val isMenuOnly: Boolean
        get() =
            kind == Kind.LISTS || kind == Kind.DASHBOARD || kind == Kind.WATCHLIST || kind == Kind.SEARCH || kind == Kind.DOWNLOADS

    /** Hidden from both Home and the side menu: only Search can reach it. */
    val isOnlyReachableFromSearch: Boolean get() = kind == Kind.LIBRARY && !isEnabled && !showInSideMenu

    /**
     * Names are what is saved: never rename one once shipped. REQUESTS is the
     * Seerr requests row (a Home row and the Requests menu entry); LISTS,
     * DASHBOARD, WATCHLIST, SEARCH and DOWNLOADS are menu-only. Append new
     * kinds at the end.
     */
    enum class Kind { CONTINUE_WATCHING, LIBRARY, LIST, REQUESTS, LISTS, DASHBOARD, WATCHLIST, SEARCH, DOWNLOADS }

    /** Stable identity used for reordering + dedupe (matches the Swift `id`). */
    val id: String
        get() =
            when (kind) {
                Kind.CONTINUE_WATCHING -> CONTINUE_WATCHING_ID
                Kind.LIBRARY -> libraryId ?: ""
                Kind.LIST -> "$LIST_ID_PREFIX${listId ?: ""}"
                Kind.REQUESTS -> REQUESTS_ID
                Kind.LISTS -> LISTS_ID
                Kind.DASHBOARD -> DASHBOARD_ID
                Kind.WATCHLIST -> WATCHLIST_ID
                Kind.SEARCH -> SEARCH_ID
                Kind.DOWNLOADS -> DOWNLOADS_ID
            }

    val displayName: String
        get() =
            when (kind) {
                Kind.CONTINUE_WATCHING -> "Continue Watching"
                Kind.LIBRARY -> "Recently Added ${libraryName ?: ""}"
                Kind.LIST -> listTitle ?: "List"
                Kind.REQUESTS -> "Requests"
                Kind.LISTS -> "Lists"
                Kind.DASHBOARD -> "Dashboard"
                Kind.WATCHLIST -> "Watchlist"
                Kind.SEARCH -> "Search"
                Kind.DOWNLOADS -> "Downloads"
            }

    companion object {
        const val CONTINUE_WATCHING_ID = "continue_watching"
        const val LIST_ID_PREFIX = "list:"

        /** The requests row's id: never changes once shipped (Apple raw value parity). */
        const val REQUESTS_ID = "requests"

        /** Menu-only keys, as Apple saves them ("menu:<destination>"). */
        const val LISTS_ID = "menu:lists"
        const val DASHBOARD_ID = "menu:dashboard"
        const val WATCHLIST_ID = "menu:watchlist"
        const val SEARCH_ID = "menu:search"
        const val DOWNLOADS_ID = "menu:downloads"

        /** Android Home has no hero row; the shared placement rule still takes its key. */
        const val HERO_ID = "hero"

        fun list(
            id: String,
            title: String,
        ) = HomeRowConfig(kind = Kind.LIST, listId = id, listTitle = title)

        fun continueWatching(isEnabled: Boolean = true): HomeRowConfig = HomeRowConfig(kind = Kind.CONTINUE_WATCHING, isEnabled = isEnabled)

        fun requests(): HomeRowConfig = HomeRowConfig(kind = Kind.REQUESTS)

        fun lists(): HomeRowConfig = HomeRowConfig(kind = Kind.LISTS)

        fun dashboard(): HomeRowConfig = HomeRowConfig(kind = Kind.DASHBOARD)

        fun watchlist(): HomeRowConfig = HomeRowConfig(kind = Kind.WATCHLIST)

        fun search(): HomeRowConfig = HomeRowConfig(kind = Kind.SEARCH)

        fun downloads(): HomeRowConfig = HomeRowConfig(kind = Kind.DOWNLOADS)

        fun library(
            id: String,
            name: String,
            isEnabled: Boolean = true,
        ) = HomeRowConfig(
            kind = Kind.LIBRARY,
            libraryId = id,
            libraryName = name,
            isEnabled = isEnabled,
        )
    }
}

/** Persistence seam so the ordering/migration logic is unit-testable. */
interface HomeRowStore {
    /** Returns null when nothing has ever been saved (first launch). */
    fun load(): List<HomeRowConfig>?

    fun save(rows: List<HomeRowConfig>)
}

/**
 * Ordered, per-library Home row configuration. Android port of the Swift
 * `HomeRowSettings` (was an ObservableObject; here it's a StateFlow holder with
 * an injected store so the reconcile logic runs in plain unit tests).
 */
class HomeRowSettings(
    private val store: HomeRowStore,
) {
    private val _rows = MutableStateFlow(loadRows())
    val rows: StateFlow<List<HomeRowConfig>> = _rows.asStateFlow()

    /**
     * Load persisted rows, or [DEFAULT_ROWS] on first launch; libraries are
     * added dynamically by [updateLibraries] (port of Swift loadRows()). A
     * saved order goes through [normalized], which migrates it.
     */
    private fun loadRows(): List<HomeRowConfig> = normalized(store.load() ?: DEFAULT_ROWS)

    /**
     * Reconcile the row list against the current library set: append rows for
     * newly-seen libraries (enabled), drop rows for libraries that no longer
     * exist, and preserve the user's existing order + visibility. Port of Swift
     * updateLibraries().
     */
    fun updateLibraries(libraries: List<JellyfinLibrary>) {
        val current = _rows.value.toMutableList()

        // Add any library not already present: after the last library row,
        // or (none yet) before the first trailing menu-only entry below Search,
        // so Lists, the Dashboard and Downloads keep following the libraries
        // until the user moves them. Search and the Watchlist lead the menu by
        // default, so neither is that anchor.
        for (library in libraries) {
            val present =
                current.any { it.kind == HomeRowConfig.Kind.LIBRARY && it.libraryId == library.id }
            if (!present) {
                val lastLibrary = current.indexOfLast { it.kind == HomeRowConfig.Kind.LIBRARY }
                val search = current.indexOfFirst { it.kind == HomeRowConfig.Kind.SEARCH }
                val firstMenuOnly =
                    current.indices.firstOrNull { i ->
                        i > search && current[i].isMenuOnly && current[i].kind !in LEADING_KINDS
                    } ?: -1
                val at =
                    when {
                        lastLibrary >= 0 -> lastLibrary + 1
                        firstMenuOnly >= 0 -> firstMenuOnly
                        else -> current.size
                    }
                current.add(at, HomeRowConfig.library(id = library.id, name = library.name))
            }
        }

        // Remove library rows whose library is gone.
        val liveIds = libraries.map { it.id }.toSet()
        current.removeAll { it.kind == HomeRowConfig.Kind.LIBRARY && it.libraryId !in liveIds }

        commit(current)
    }

    /**
     * Reconcile list rows against the user's subscriptions ([lists]: playlist
     * id to row title, in subscription order). A new subscription's row goes
     * right after Continue Watching ([SharedLists.insertionIndex]); after that
     * it is an ordinary row the user can move or hide. A list no longer
     * subscribed (or gone from the server) loses its row. Titles refresh.
     */
    fun updateListRows(lists: List<Pair<String, String>>) {
        val current = _rows.value.toMutableList()
        val wanted = lists.toMap()
        current.removeAll { it.kind == HomeRowConfig.Kind.LIST && it.listId !in wanted }
        for (i in current.indices) {
            val row = current[i]
            if (row.kind == HomeRowConfig.Kind.LIST) wanted[row.listId]?.let { current[i] = row.copy(listTitle = it) }
        }
        val present = current.mapNotNull { it.listId }.toSet()
        var at = SharedLists.insertionIndex(current.map { it.id }, HomeRowConfig.CONTINUE_WATCHING_ID, HomeRowConfig.HERO_ID)
        for ((id, title) in lists) {
            if (id in present) continue
            current.add(at++, HomeRowConfig.list(id, title))
        }
        if (current != _rows.value) commit(current)
    }

    /** Move a row (drag-to-reorder). No-op if indices are out of range. */
    fun moveRow(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val list = _rows.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        list.add(toIndex, list.removeAt(fromIndex))
        commit(list)
    }

    /** Move the row [fromId] to where [toId] is now (the screen lists some rows only while they apply). */
    fun moveRow(
        fromId: String,
        toId: String,
    ) {
        val list = _rows.value
        moveRow(list.indexOfFirst { it.id == fromId }, list.indexOfFirst { it.id == toId })
    }

    /** Toggle a row's visibility by its stable id. Menu-only entries have no Home visibility. */
    fun toggleRow(id: String) {
        val list =
            _rows.value.map { if (it.id == id && !it.isMenuOnly) it.copy(isEnabled = !it.isEnabled) else it }
        commit(list)
    }

    /** Toggle a row's side-menu visibility. Rows that are not destinations are left alone. */
    fun toggleSideMenu(id: String) {
        val list =
            _rows.value.map {
                if (it.id == id && it.isSideMenuDestination) it.copy(showInSideMenu = !it.showInSideMenu) else it
            }
        commit(list)
    }

    fun setRows(rows: List<HomeRowConfig>) = commit(normalized(rows))

    companion object {
        private val LEADING_KINDS = setOf(HomeRowConfig.Kind.SEARCH, HomeRowConfig.Kind.WATCHLIST)

        /**
         * A fresh install's order (owner, 2026-11-12; the same on every
         * Sashimi platform): Search, Watchlist, then the libraries (added by
         * [updateLibraries] before Lists), Lists, Requests, Dashboard,
         * Downloads. Continue Watching is a Home-only row and sits near the top.
         */
        val DEFAULT_ROWS: List<HomeRowConfig> =
            listOf(
                HomeRowConfig.search(),
                HomeRowConfig.watchlist(),
                HomeRowConfig.continueWatching(),
                HomeRowConfig.lists(),
                HomeRowConfig.requests(),
                HomeRowConfig.dashboard(),
                HomeRowConfig.downloads(),
            )

        /**
         * Adds the built-in entries a saved order is missing, without moving
         * anything the user placed: the requests row right after Continue
         * Watching (where Apple drew it before it was a row; the start if
         * there is none), then Lists and the Dashboard at the end, which is
         * where the side menu has always listed Lists.
         *
         * An order with no Search entry predates the side-menu parity change
         * (2026-11-12): Search goes first with the Watchlist directly under
         * it, as the side menu has always drawn them, and everything else
         * keeps its relative order. Once Search is saved nothing moves again.
         * Downloads, when missing, goes last, where the menu has always drawn it.
         */
        fun normalized(rows: List<HomeRowConfig>): List<HomeRowConfig> {
            val result = rows.toMutableList()
            if (result.none { it.kind == HomeRowConfig.Kind.REQUESTS }) {
                val cw = result.indexOfFirst { it.kind == HomeRowConfig.Kind.CONTINUE_WATCHING }
                result.add(if (cw >= 0) cw + 1 else 0, HomeRowConfig.requests())
            }
            if (result.none { it.kind == HomeRowConfig.Kind.LISTS }) result.add(HomeRowConfig.lists())
            if (result.none { it.kind == HomeRowConfig.Kind.DASHBOARD }) result.add(HomeRowConfig.dashboard())
            if (result.none { it.kind == HomeRowConfig.Kind.SEARCH }) {
                val watchlist = result.firstOrNull { it.kind == HomeRowConfig.Kind.WATCHLIST } ?: HomeRowConfig.watchlist()
                result.removeAll { it.kind == HomeRowConfig.Kind.WATCHLIST }
                result.addAll(0, listOf(HomeRowConfig.search(), watchlist))
            } else if (result.none { it.kind == HomeRowConfig.Kind.WATCHLIST }) {
                result.add(result.indexOfFirst { it.kind == HomeRowConfig.Kind.SEARCH } + 1, HomeRowConfig.watchlist())
            }
            if (result.none { it.kind == HomeRowConfig.Kind.DOWNLOADS }) result.add(HomeRowConfig.downloads())
            return result
        }
    }

    private fun commit(rows: List<HomeRowConfig>) {
        _rows.value = rows
        store.save(rows)
    }
}
