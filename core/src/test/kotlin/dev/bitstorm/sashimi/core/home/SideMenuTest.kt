package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideMenuTest {
    private val movies = JellyfinLibrary("movies", "Movies", "movies")
    private val tv = JellyfinLibrary("tv", "TV Shows", "tvshows")
    private val anime = JellyfinLibrary("anime", "Anime", "tvshows")
    private val kids = JellyfinLibrary("kids", "Kids", "movies")

    private fun keys(items: List<SideMenuItem>) = items.map { it.key }

    private fun row(
        library: JellyfinLibrary,
        home: Boolean = true,
        menu: Boolean = true,
    ) = HomeRowConfig.library(library.id, library.name, isEnabled = home).copy(showInSideMenu = menu)

    @Test
    fun `libraries follow the Home row order between Home and Search`() {
        val rows = listOf(HomeRowConfig.continueWatching(), row(tv), row(movies))
        assertEquals(
            listOf("home", "library:tv", "library:movies", "search", "downloads", "settings"),
            keys(SideMenu.items(rows, listOf(movies, tv))),
        )
    }

    @Test
    fun `a row hidden from the side menu drops, and Home visibility has no say`() {
        val rows = listOf(row(movies), row(tv, menu = false), row(anime, home = false), row(kids, home = false, menu = false))
        assertEquals(
            listOf("home", "library:movies", "library:anime", "search", "downloads", "settings"),
            keys(SideMenu.items(rows, listOf(movies, tv, anime, kids))),
        )
    }

    @Test
    fun `a library added on the server since is shown, after the saved order`() {
        val rows = listOf(row(tv))
        assertEquals(
            listOf("home", "library:tv", "library:movies", "search", "downloads", "settings"),
            keys(SideMenu.items(rows, listOf(movies, tv))),
        )
    }

    @Test
    fun `a row naming a library the server dropped is ignored`() {
        val rows = listOf(row(kids), row(movies))
        assertEquals(listOf("home", "library:movies", "search", "downloads", "settings"), keys(SideMenu.items(rows, listOf(movies))))
    }

    @Test
    fun `Home, Search, Downloads and Settings are always there, even with every library hidden`() {
        val rows = listOf(row(movies, menu = false), row(tv, menu = false))
        val items = SideMenu.items(rows, listOf(movies, tv))
        assertEquals(SideMenu.ALWAYS_SHOWN, items)
        assertFalse(items.any { it is SideMenuItem.Library })
    }

    @Test
    fun `there is no separate Libraries entry`() {
        val items = SideMenu.items(listOf(row(movies)), listOf(movies))
        assertEquals(1, items.count { it is SideMenuItem.Library })
        assertFalse(keys(items).contains("libraries"))
    }

    @Test
    fun `offline lists only what works without the server`() {
        assertEquals(listOf("home", "downloads", "settings"), keys(SideMenu.items(listOf(row(movies)), listOf(movies), online = false)))
    }

    @Test
    fun `only library rows have a side-menu toggle and the search-only footnote`() {
        assertFalse(HomeRowConfig.continueWatching().isSideMenuDestination)
        assertTrue(row(movies, home = false, menu = false).isOnlyReachableFromSearch)
        assertFalse(row(movies, home = true, menu = false).isOnlyReachableFromSearch)
        assertFalse(row(movies, home = false, menu = true).isOnlyReachableFromSearch)
    }

    @Test
    fun `saved rows from before the setting existed decode as shown`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """[{"kind":"LIBRARY","libraryId":"movies","libraryName":"Movies","isEnabled":false}]"""
        assertTrue(json.decodeFromString<List<HomeRowConfig>>(old).single().showInSideMenu)
    }
}
