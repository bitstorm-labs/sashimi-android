package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.settingssync.HomeRowsLayout.Entry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsLayoutTest {
    private val cw = HomeRowConfig.continueWatching()
    private val requests = HomeRowConfig.requests()
    private val movies = HomeRowConfig.library("m", "Movies")
    private val shows = HomeRowConfig.library("s", "Shows")
    private val anime = HomeRowConfig.library("a", "Anime")
    private val list = HomeRowConfig.list("p1", "From Marge: Xmas")
    private val watchlist = HomeRowConfig.watchlist()
    private val menuLists = HomeRowConfig.lists()
    private val dashboard = HomeRowConfig.dashboard()

    private fun ids(rows: List<HomeRowConfig>) = rows.map { it.id }

    private fun layoutIds(array: JsonArray) = array.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `local ids map to shared ids and menu-only rows have none`() {
        assertEquals("continueWatching", HomeRowsLayout.sharedId(cw))
        assertEquals("requests", HomeRowsLayout.sharedId(requests))
        assertEquals("library:m", HomeRowsLayout.sharedId(movies))
        assertEquals("list:p1", HomeRowsLayout.sharedId(list))
        assertNull(HomeRowsLayout.sharedId(watchlist))
        assertNull(HomeRowsLayout.sharedId(menuLists))
        assertNull(HomeRowsLayout.sharedId(dashboard))
    }

    @Test
    fun `applying takes the layout's order and on flags`() {
        val local = listOf(cw, requests, movies, shows)
        val remote =
            listOf(
                Entry("hero", true),
                Entry("library:s", true),
                Entry("continueWatching", false),
                Entry("library:m", true),
                Entry("requests", false),
                Entry("fintv", true),
            )

        val applied = HomeRowsLayout.apply(local, remote)

        assertEquals(listOf("s", "continue_watching", "m", "requests"), ids(applied))
        assertEquals(listOf(true, false, true, false), applied.map { it.isEnabled })
    }

    @Test
    fun `V9 a library the remote layout lacks stays after its local predecessor`() {
        val local = listOf(cw, movies, anime, shows)
        val remote = listOf(Entry("library:s", true), Entry("continueWatching", true), Entry("library:m", true))

        assertEquals(listOf("s", "continue_watching", "m", "a"), ids(HomeRowsLayout.apply(local, remote)))
    }

    @Test
    fun `menu-only entries are carried through untouched where they were`() {
        val local = listOf(watchlist, cw, requests, movies, menuLists, dashboard)
        val remote = listOf(Entry("library:m", true), Entry("requests", true), Entry("continueWatching", true))

        val applied = HomeRowsLayout.apply(local, remote)

        assertEquals(listOf("menu:watchlist", "m", "menu:lists", "menu:dashboard", "requests", "continue_watching"), ids(applied))
    }

    @Test
    fun `V8 writing keeps the remote hero second`() {
        val remote =
            Json.parseToJsonElement(
                """[{"id":"continueWatching","on":true},{"id":"hero","on":true},{"id":"library:m","on":true},{"id":"library:s","on":false}]""",
            )
        val local = listOf(cw, shows, movies)

        val written = HomeRowsLayout.write(local, remote)

        assertEquals(listOf("continueWatching", "hero", "library:s", "library:m"), layoutIds(written))
        assertEquals(1, layoutIds(written).indexOf("hero"))
    }

    @Test
    fun `writing carries unknown ids and their extra fields through after their remote predecessor`() {
        val remote =
            Json.parseToJsonElement(
                """[{"id":"future:x","on":true,"extra":7},{"id":"continueWatching","on":true},{"id":"fintv","on":false},{"id":"list:gone","on":true}]""",
            )
        val local = listOf(watchlist, cw, movies, menuLists)

        val written = HomeRowsLayout.write(local, remote)

        assertEquals(listOf("future:x", "continueWatching", "fintv", "list:gone", "library:m"), layoutIds(written))
        assertEquals(JsonPrimitive(7), written[0].jsonObject["extra"])
        assertEquals(JsonPrimitive(false), written[2].jsonObject["on"])
    }

    @Test
    fun `writing over no or an invalid remote layout is just this device's rows`() {
        val local = listOf(watchlist, cw, HomeRowConfig.library("m", "Movies", isEnabled = false), list)

        val expected =
            Json.parseToJsonElement(
                """[{"id":"continueWatching","on":true},{"id":"library:m","on":false},{"id":"list:p1","on":true}]""",
            )
        assertEquals(expected, HomeRowsLayout.write(local, null))
        assertEquals(expected, HomeRowsLayout.write(local, Json.parseToJsonElement("""[{"id":1}]""")))
    }

    @Test
    fun `a layout must be an array of id and on`() {
        assertTrue(HomeRowsLayout.decode(Json.parseToJsonElement("""[{"id":"hero","on":true}]""")) != null)
        assertNull(HomeRowsLayout.decode(Json.parseToJsonElement("""{"id":"hero"}""")))
        assertNull(HomeRowsLayout.decode(Json.parseToJsonElement("""[{"id":"hero","on":"true"}]""")))
        assertNull(HomeRowsLayout.decode(Json.parseToJsonElement("""[{"on":true}]""")))
        assertNull(HomeRowsLayout.decode(Json.parseToJsonElement("""["hero"]""")))
    }

    @Test
    fun `a round trip through the shared layout keeps the local order`() {
        val local = listOf(watchlist, shows, cw, HomeRowConfig.library("m", "Movies", isEnabled = false), requests, menuLists)
        val layout = HomeRowsLayout.entries(HomeRowsLayout.write(local, null))!!

        assertEquals(local, HomeRowsLayout.apply(local, layout))
    }

    @Test
    fun `reordering or hiding a row is a user change, rows coming and going are not`() {
        val before = listOf(watchlist, cw, requests, movies, shows)

        assertTrue(HomeRowsLayout.isUserChange(before, listOf(watchlist, cw, requests, shows, movies)))
        assertTrue(HomeRowsLayout.isUserChange(before, listOf(watchlist, cw.copy(isEnabled = false), requests, movies, shows)))
        assertFalse("a new library", HomeRowsLayout.isUserChange(before, before + anime))
        assertFalse("a library gone", HomeRowsLayout.isUserChange(before, before - shows))
        assertFalse("a list subscribed", HomeRowsLayout.isUserChange(before, listOf(watchlist, cw, list, requests, movies, shows)))
        assertFalse("a side-menu toggle", HomeRowsLayout.isUserChange(before, before.map { it.copy(showInSideMenu = false) }))
        assertFalse("a menu-only move", HomeRowsLayout.isUserChange(before, listOf(cw, requests, watchlist, movies, shows)))
        assertFalse("the first save", HomeRowsLayout.isUserChange(null, before))
    }

    @Test
    fun `seeding counts a hidden or reordered layout, not one grown by default`() {
        assertFalse(HomeRowsLayout.isCustomised(listOf(watchlist, cw, list, requests, movies, shows, menuLists, dashboard)))
        assertFalse(HomeRowsLayout.isCustomised(listOf(watchlist, cw, requests, menuLists, dashboard)))
        assertTrue(HomeRowsLayout.isCustomised(listOf(cw, movies, requests)))
        assertTrue(HomeRowsLayout.isCustomised(listOf(movies, cw, requests)))
        assertTrue(HomeRowsLayout.isCustomised(listOf(cw, requests, movies.copy(isEnabled = false))))
    }
}
