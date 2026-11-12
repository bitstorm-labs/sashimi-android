package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class WatchlistRowStore(
    var saved: List<HomeRowConfig>? = null,
) : HomeRowStore {
    override fun load(): List<HomeRowConfig>? = saved

    override fun save(rows: List<HomeRowConfig>) {
        saved = rows
    }
}

/**
 * The Watchlist is a primary destination (owner, 2026-11-11): directly under
 * Search by default, reorderable and hideable in Home & Side Menu like any
 * other menu entry, and a tab in the phone bar right after Search.
 */
class WatchlistNavTest {
    private val movies = JellyfinLibrary("movies", "Movies", "movies")
    private val tv = JellyfinLibrary("tv", "TV Shows", "tvshows")

    private fun keys(items: List<SideMenuItem>) = items.map { it.key }

    @Test
    fun `the watchlist entry is a menu-only side-menu destination with a stable id`() {
        val row = HomeRowConfig.watchlist()
        assertEquals("menu:watchlist", row.id)
        assertEquals("Watchlist", row.displayName)
        assertTrue(row.isSideMenuDestination)
        assertTrue("Home draws no row for it", row.isMenuOnly)
        assertFalse(row.isOnlyReachableFromSearch)
    }

    @Test
    fun `a new install lists the Watchlist directly under Search, ahead of the libraries`() {
        val settings = HomeRowSettings(WatchlistRowStore(saved = null))
        settings.updateLibraries(listOf(movies, tv))
        assertEquals(
            listOf(
                "menu:search",
                "menu:watchlist",
                "continue_watching",
                "movies",
                "tv",
                "menu:lists",
                "requests",
                "menu:dashboard",
                "menu:downloads",
            ),
            settings.rows.value.map { it.id },
        )
        assertEquals(
            listOf("home", "search", "watchlist", "library:movies", "library:tv", "lists", "downloads", "settings"),
            keys(SideMenu.items(settings.rows.value, listOf(movies, tv), showLists = true)),
        )
    }

    @Test
    fun `a saved order from before the Watchlist existed gains it under Search and nothing else moves`() {
        val saved =
            listOf(
                HomeRowConfig.library("tv", "TV Shows"),
                HomeRowConfig.continueWatching(),
                HomeRowConfig.requests(),
                HomeRowConfig.lists(),
                HomeRowConfig.library("movies", "Movies").copy(showInSideMenu = false),
                HomeRowConfig.dashboard(),
            )
        val store = WatchlistRowStore(saved)
        val settings = HomeRowSettings(store)
        assertEquals(
            listOf(
                "menu:search",
                "menu:watchlist",
                "tv",
                "continue_watching",
                "requests",
                "menu:lists",
                "movies",
                "menu:dashboard",
                "menu:downloads",
            ),
            settings.rows.value.map { it.id },
        )
        assertFalse(
            settings.rows.value
                .first { it.id == "movies" }
                .showInSideMenu,
        )
    }

    @Test
    fun `a library added later never jumps ahead of the Watchlist`() {
        val settings = HomeRowSettings(WatchlistRowStore(saved = listOf(HomeRowConfig.watchlist())))
        settings.updateLibraries(listOf(movies))
        val ids = settings.rows.value.map { it.id }
        assertTrue(ids.indexOf("menu:watchlist") < ids.indexOf("movies"))
    }

    @Test
    fun `a moved Watchlist keeps its place, and hiding it takes it out of the menu`() {
        val settings = HomeRowSettings(WatchlistRowStore(saved = null))
        settings.updateLibraries(listOf(movies, tv))
        settings.moveRow(fromId = "menu:watchlist", toId = "tv")
        assertEquals(
            listOf("home", "search", "library:movies", "library:tv", "watchlist", "lists", "downloads", "settings"),
            keys(SideMenu.items(settings.rows.value, listOf(movies, tv), showLists = true)),
        )

        settings.toggleSideMenu("menu:watchlist")
        assertFalse(keys(SideMenu.items(settings.rows.value, listOf(movies, tv))).contains("watchlist"))
        assertFalse(SideMenu.showsWatchlist(settings.rows.value))
    }

    @Test
    fun `the Home toggle does nothing to the menu-only Watchlist`() {
        val settings = HomeRowSettings(WatchlistRowStore(saved = null))
        val before = settings.rows.value
        settings.toggleRow("menu:watchlist")
        assertEquals(before, settings.rows.value)
    }

    @Test
    fun `offline the Watchlist is not listed, it needs the server`() {
        val rows = HomeRowSettings.normalized(listOf(HomeRowConfig.continueWatching()))
        assertEquals(listOf("home", "downloads", "settings"), keys(SideMenu.items(rows, listOf(movies), online = false)))
    }

    @Test
    fun `the phone bar has the Watchlist right after Search`() {
        assertEquals(
            listOf(
                PrimaryTab.HOME,
                PrimaryTab.LIBRARIES,
                PrimaryTab.LISTS,
                PrimaryTab.SEARCH,
                PrimaryTab.WATCHLIST,
                PrimaryTab.DOWNLOADS,
                PrimaryTab.SETTINGS,
            ),
            PrimaryTab.phoneTabs(online = true, showLists = true, showWatchlist = true),
        )
    }

    @Test
    fun `the phone bar follows the side-menu switches and offline drops what needs the server`() {
        assertEquals(
            listOf(PrimaryTab.HOME, PrimaryTab.LIBRARIES, PrimaryTab.SEARCH, PrimaryTab.DOWNLOADS, PrimaryTab.SETTINGS),
            PrimaryTab.phoneTabs(online = true, showLists = false, showWatchlist = false),
        )
        assertEquals(
            listOf(PrimaryTab.HOME, PrimaryTab.DOWNLOADS, PrimaryTab.SETTINGS),
            PrimaryTab.phoneTabs(online = false, showLists = true, showWatchlist = true),
        )
    }

    @Test
    fun `rows that never mention the Watchlist still show it`() {
        assertTrue(SideMenu.showsWatchlist(listOf(HomeRowConfig.continueWatching())))
        assertEquals(
            listOf("home", "search", "watchlist", "library:movies", "downloads", "settings"),
            keys(SideMenu.items(listOf(HomeRowConfig.library("movies", "Movies")), listOf(movies))),
        )
    }
}
