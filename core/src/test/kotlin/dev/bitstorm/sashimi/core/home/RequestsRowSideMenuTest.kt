package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryRowStore(
    var saved: List<HomeRowConfig>? = null,
) : HomeRowStore {
    override fun load(): List<HomeRowConfig>? = saved

    override fun save(rows: List<HomeRowConfig>) {
        saved = rows
    }
}

/**
 * The side menu lists its destinations in Home's row order (Apple parity,
 * 2026-11-07 brief section A): Requests is a real Home row that carries its
 * menu entry, and Lists and the Dashboard are menu-only entries placed in
 * the same order.
 */
class RequestsRowSideMenuTest {
    private val movies = JellyfinLibrary("movies", "Movies", "movies")
    private val tv = JellyfinLibrary("tv", "TV Shows", "tvshows")

    private fun keys(items: List<SideMenuItem>) = items.map { it.key }

    private fun all(
        rows: List<HomeRowConfig>,
        libraries: List<JellyfinLibrary> = listOf(movies, tv),
    ) = SideMenu.items(rows, libraries, showLists = true, showRequests = true, showDashboard = true)

    @Test
    fun `requests is a built-in Home row with a stable id and its own menu entry`() {
        val row = HomeRowConfig.requests()
        assertEquals("requests", row.id)
        assertEquals("Requests", row.displayName)
        assertTrue("movable and hideable like any row, with a side-menu toggle", row.isSideMenuDestination)
        assertFalse(row.isMenuOnly)
        assertTrue(HomeRowConfig.lists().isMenuOnly)
        assertTrue(HomeRowConfig.dashboard().isMenuOnly)
    }

    @Test
    fun `a new install puts Lists, Requests and the Dashboard after the libraries`() {
        val settings = HomeRowSettings(MemoryRowStore(saved = null))
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
    }

    @Test
    fun `a saved order without the requests row gets it right after Continue Watching and nothing else moves`() {
        val saved =
            listOf(
                HomeRowConfig.library("tv", "TV Shows"),
                HomeRowConfig.continueWatching(),
                HomeRowConfig.list("p1", "Christmas"),
                HomeRowConfig.library("movies", "Movies"),
            )
        val settings = HomeRowSettings(MemoryRowStore(saved))
        assertEquals(
            listOf(
                "menu:search",
                "menu:watchlist",
                "tv",
                "continue_watching",
                "requests",
                "list:p1",
                "movies",
                "menu:lists",
                "menu:dashboard",
                "menu:downloads",
            ),
            settings.rows.value.map { it.id },
        )
        // And the menu reads exactly as before for what was there already.
        assertEquals(
            listOf("home", "search", "watchlist", "library:tv", "library:movies", "lists", "downloads", "settings"),
            keys(SideMenu.items(settings.rows.value, listOf(movies, tv), showLists = true)),
        )
    }

    @Test
    fun `moving the requests row moves its menu entry`() {
        val settings = HomeRowSettings(MemoryRowStore(saved = null))
        settings.updateLibraries(listOf(movies, tv))
        assertEquals(
            listOf(
                "home",
                "search",
                "watchlist",
                "library:movies",
                "library:tv",
                "lists",
                "requests",
                "dashboard",
                "downloads",
                "settings",
            ),
            keys(all(settings.rows.value)),
        )
        settings.moveRow(fromId = "requests", toId = "movies")
        assertEquals(
            listOf(
                "home",
                "search",
                "watchlist",
                "requests",
                "library:movies",
                "library:tv",
                "lists",
                "dashboard",
                "downloads",
                "settings",
            ),
            keys(all(settings.rows.value)),
        )
    }

    @Test
    fun `lists and the dashboard move like rows`() {
        val settings = HomeRowSettings(MemoryRowStore(saved = null))
        settings.updateLibraries(listOf(movies, tv))
        settings.moveRow(fromId = "menu:dashboard", toId = "requests")
        settings.moveRow(fromId = "menu:lists", toId = "movies")
        assertEquals(
            listOf(
                "home",
                "search",
                "watchlist",
                "lists",
                "library:movies",
                "library:tv",
                "dashboard",
                "requests",
                "downloads",
                "settings",
            ),
            keys(all(settings.rows.value)),
        )
    }

    @Test
    fun `the menu's destinations are in exactly the rows' relative order`() {
        val rows =
            listOf(
                HomeRowConfig.lists(),
                HomeRowConfig.library("tv", "TV Shows"),
                HomeRowConfig.downloads(),
                HomeRowConfig.continueWatching(),
                HomeRowConfig.dashboard(),
                HomeRowConfig.list("p1", "Christmas"),
                HomeRowConfig.requests(),
                HomeRowConfig.search(),
                HomeRowConfig.watchlist(),
                HomeRowConfig.library("movies", "Movies"),
            )
        val menu = keys(all(rows)).filterNot { it in setOf("home", "settings") }
        val fromRows =
            rows.mapNotNull {
                when (it.kind) {
                    HomeRowConfig.Kind.LIBRARY -> "library:${it.libraryId}"
                    HomeRowConfig.Kind.REQUESTS -> "requests"
                    HomeRowConfig.Kind.LISTS -> "lists"
                    HomeRowConfig.Kind.DASHBOARD -> "dashboard"
                    HomeRowConfig.Kind.WATCHLIST -> "watchlist"
                    HomeRowConfig.Kind.SEARCH -> "search"
                    HomeRowConfig.Kind.DOWNLOADS -> "downloads"
                    HomeRowConfig.Kind.CONTINUE_WATCHING, HomeRowConfig.Kind.LIST -> null
                }
            }
        assertEquals(fromRows, menu)
    }

    @Test
    fun `hiding the requests row on Home keeps the menu entry, the menu toggle drops it`() {
        val base = listOf(HomeRowConfig.continueWatching(), HomeRowConfig.requests(), HomeRowConfig.library("movies", "Movies"))
        val offHome = base.map { if (it.kind == HomeRowConfig.Kind.REQUESTS) it.copy(isEnabled = false) else it }
        assertTrue(keys(all(offHome, listOf(movies))).contains("requests"))
        val offMenu = base.map { if (it.kind == HomeRowConfig.Kind.REQUESTS) it.copy(showInSideMenu = false) else it }
        assertFalse(keys(all(offMenu, listOf(movies))).contains("requests"))
    }

    @Test
    fun `requests and dashboard are listed only while available`() {
        val settings = HomeRowSettings(MemoryRowStore(saved = null))
        settings.updateLibraries(listOf(movies))
        val none = keys(SideMenu.items(settings.rows.value, listOf(movies), showLists = true))
        assertFalse(none.contains("requests"))
        assertFalse(none.contains("dashboard"))
        assertEquals(
            listOf("home", "search", "watchlist", "library:movies", "lists", "dashboard", "downloads", "settings"),
            keys(SideMenu.items(settings.rows.value, listOf(movies), showLists = true, showDashboard = true)),
        )
    }

    @Test
    fun `offline lists only what works without the server`() {
        assertEquals(
            listOf("home", "downloads", "settings"),
            keys(
                SideMenu.items(listOf(HomeRowConfig.requests()), listOf(movies), online = false, showRequests = true, showDashboard = true),
            ),
        )
    }

    @Test
    fun `a library added later lands after the last library, not after Lists`() {
        val settings = HomeRowSettings(MemoryRowStore(saved = null))
        settings.updateLibraries(listOf(movies))
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
    }

    @Test
    fun `the requests row hides on Home and in the menu, menu-only entries have no Home toggle`() {
        val settings = HomeRowSettings(MemoryRowStore(saved = null))
        settings.toggleRow("requests")
        assertFalse(
            settings.rows.value
                .first { it.id == "requests" }
                .isEnabled,
        )
        settings.toggleSideMenu("requests")
        assertFalse(
            settings.rows.value
                .first { it.id == "requests" }
                .showInSideMenu,
        )
        // Menu-only entries have no Home toggle.
        settings.toggleRow("menu:dashboard")
        assertTrue(
            settings.rows.value
                .first { it.id == "menu:dashboard" }
                .isEnabled,
        )
    }
}
