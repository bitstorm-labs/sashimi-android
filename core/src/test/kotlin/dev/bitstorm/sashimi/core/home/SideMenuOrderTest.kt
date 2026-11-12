package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class OrderRowStore(
    var saved: List<HomeRowConfig>? = null,
) : HomeRowStore {
    override fun load(): List<HomeRowConfig>? = saved

    override fun save(rows: List<HomeRowConfig>) {
        saved = rows
    }
}

/**
 * Side-menu parity (owner, 2026-11-12): Home first and Settings last are the
 * only pinned entries. Search and Downloads are ordinary entries in the one
 * order Home & Side Menu edits, each with its own side-menu switch.
 */
class SideMenuOrderTest {
    private val movies = JellyfinLibrary("movies", "Movies", "movies")
    private val tv = JellyfinLibrary("tv", "TV Shows", "tvshows")

    private fun keys(items: List<SideMenuItem>) = items.map { it.key }

    private fun everything(rows: List<HomeRowConfig>) =
        keys(SideMenu.items(rows, listOf(movies, tv), showLists = true, showRequests = true, showDashboard = true))

    @Test
    fun `search and downloads are menu-only side-menu destinations with stable ids`() {
        val search = HomeRowConfig.search()
        val downloads = HomeRowConfig.downloads()
        assertEquals("menu:search", search.id)
        assertEquals("menu:downloads", downloads.id)
        assertEquals("Search", search.displayName)
        assertEquals("Downloads", downloads.displayName)
        for (row in listOf(search, downloads)) {
            assertTrue(row.isSideMenuDestination)
            assertTrue("Home draws no row for it", row.isMenuOnly)
            assertFalse(row.isOnlyReachableFromSearch)
        }
    }

    @Test
    fun `a fresh install orders Search, Watchlist, libraries, Lists, Requests, Dashboard, Downloads`() {
        val settings = HomeRowSettings(OrderRowStore(saved = null))
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
            everything(settings.rows.value),
        )
    }

    @Test
    fun `a saved order gains Search on top with the Watchlist under it and Downloads last, nothing else moves`() {
        val saved =
            listOf(
                HomeRowConfig.continueWatching(),
                HomeRowConfig.requests(),
                HomeRowConfig.library("tv", "TV Shows"),
                HomeRowConfig.watchlist(),
                HomeRowConfig.library("movies", "Movies").copy(showInSideMenu = false),
                HomeRowConfig.lists(),
                HomeRowConfig.dashboard(),
            )
        val store = OrderRowStore(saved)
        val migrated = HomeRowSettings(store).rows.value
        assertEquals(
            listOf(
                "menu:search",
                "menu:watchlist",
                "continue_watching",
                "requests",
                "tv",
                "movies",
                "menu:lists",
                "menu:dashboard",
                "menu:downloads",
            ),
            migrated.map { it.id },
        )
        assertFalse("settings survive the move", migrated.first { it.id == "movies" }.showInSideMenu)
        assertEquals("normalising twice is stable", migrated, HomeRowSettings.normalized(migrated))
        assertEquals(
            listOf("home", "search", "watchlist", "requests", "library:tv", "lists", "dashboard", "downloads", "settings"),
            everything(migrated),
        )
    }

    @Test
    fun `a library added after the migration lands with the libraries, not above Search`() {
        val settings = HomeRowSettings(OrderRowStore(saved = listOf(HomeRowConfig.continueWatching())))
        settings.updateLibraries(listOf(movies))
        val ids = settings.rows.value.map { it.id }
        assertTrue(ids.indexOf("menu:search") < ids.indexOf("movies"))
        assertTrue(ids.indexOf("menu:watchlist") < ids.indexOf("movies"))
        assertTrue(ids.indexOf("movies") < ids.indexOf("menu:downloads"))
    }

    @Test
    fun `Search and the Watchlist stay where the user put them after a reload`() {
        val store = OrderRowStore(saved = null)
        val settings = HomeRowSettings(store)
        settings.updateLibraries(listOf(movies, tv))
        settings.moveRow(fromId = "menu:search", toId = "menu:dashboard")
        settings.moveRow(fromId = "menu:watchlist", toId = "tv")

        val reloaded = HomeRowSettings(store)
        reloaded.updateLibraries(listOf(movies, tv))
        assertEquals(settings.rows.value, reloaded.rows.value)
        assertEquals(reloaded.rows.value, HomeRowSettings.normalized(reloaded.rows.value))
        assertEquals(
            listOf(
                "home",
                "library:movies",
                "library:tv",
                "watchlist",
                "lists",
                "requests",
                "dashboard",
                "search",
                "downloads",
                "settings",
            ),
            everything(reloaded.rows.value),
        )
    }

    @Test
    fun `hiding Search and Downloads takes them out of the menu, Home and Settings stay`() {
        val settings = HomeRowSettings(OrderRowStore(saved = null))
        settings.updateLibraries(listOf(movies, tv))
        settings.toggleSideMenu("menu:search")
        settings.toggleSideMenu("menu:downloads")
        val shown = everything(settings.rows.value)
        assertFalse(shown.contains("search"))
        assertFalse(shown.contains("downloads"))
        assertEquals("home", shown.first())
        assertEquals("settings", shown.last())

        val allHidden = settings.rows.value.map { if (it.isSideMenuDestination) it.copy(showInSideMenu = false) else it }
        assertEquals(SideMenu.ALWAYS_SHOWN, SideMenu.items(allHidden, listOf(movies, tv)))
        assertEquals(listOf(SideMenuItem.Home, SideMenuItem.Settings), SideMenu.ALWAYS_SHOWN)
        assertEquals("Always shown: Home, Settings", SideMenu.ALWAYS_SHOWN_NOTE)
    }

    @Test
    fun `the Home toggle does nothing to Search or Downloads`() {
        val settings = HomeRowSettings(OrderRowStore(saved = null))
        val before = settings.rows.value
        settings.toggleRow("menu:search")
        settings.toggleRow("menu:downloads")
        assertEquals(before, settings.rows.value)
    }

    @Test
    fun `offline lists Home, Downloads, Settings, Downloads even when hidden`() {
        val rows =
            HomeRowSettings
                .normalized(listOf(HomeRowConfig.continueWatching()))
                .map { if (it.kind == HomeRowConfig.Kind.DOWNLOADS) it.copy(showInSideMenu = false) else it }
        assertEquals(listOf("home", "downloads", "settings"), keys(SideMenu.items(rows, listOf(movies), online = false)))
    }

    @Test
    fun `saved Search and Downloads rows decode, and their kind names are what is saved`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val saved = """[{"kind":"SEARCH","showInSideMenu":false},{"kind":"DOWNLOADS"}]"""
        val rows = json.decodeFromString<List<HomeRowConfig>>(saved)
        assertEquals(listOf(HomeRowConfig.Kind.SEARCH, HomeRowConfig.Kind.DOWNLOADS), rows.map { it.kind })
        assertFalse(rows.first().showInSideMenu)
    }
}
