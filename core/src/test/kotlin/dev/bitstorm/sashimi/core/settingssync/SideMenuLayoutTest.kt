package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.home.SideMenu
import dev.bitstorm.sashimi.core.home.SideMenuItem
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import dev.bitstorm.sashimi.core.settingssync.SideMenuLayout.Entry
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

/** The shared `sideMenu` value on Android (settings-sync schema, phase 2, S1–S6). */
class SideMenuLayoutTest {
    private val search = HomeRowConfig.search()
    private val watchlist = HomeRowConfig.watchlist()
    private val cw = HomeRowConfig.continueWatching()
    private val movies = HomeRowConfig.library("m", "Movies")
    private val shows = HomeRowConfig.library("s", "Shows")
    private val list = HomeRowConfig.list("p1", "From Marge: Xmas")
    private val lists = HomeRowConfig.lists()
    private val requests = HomeRowConfig.requests()
    private val dashboard = HomeRowConfig.dashboard()
    private val downloads = HomeRowConfig.downloads()

    /** A fresh install's order once the server's libraries are in. */
    private val fresh = listOf(search, watchlist, cw, movies, shows, lists, requests, dashboard, downloads)

    private fun ids(rows: List<HomeRowConfig>) = rows.map { it.id }

    private fun layoutIds(array: JsonArray) = array.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `every local entry has a shared id and destinations carry a menu flag`() {
        assertEquals(
            listOf(
                Entry("search", true),
                Entry("watchlist", true),
                Entry("continueWatching", null),
                Entry("library:m", true),
                Entry("list:p1", null),
                Entry("lists", false),
                Entry("requests", true),
                Entry("dashboard", true),
                Entry("downloads", false),
            ),
            SideMenuLayout.project(
                listOf(search, watchlist, cw, movies, list, lists, requests, dashboard, downloads.copy(showInSideMenu = false)),
                showLists = false,
            ),
        )
    }

    @Test
    fun `S1 a remote order with Lists before Search is followed, Home and Settings stay fixed`() {
        val remote =
            SideMenuLayout.entries(
                Json.parseToJsonElement(
                    """[{"id":"lists","menu":true},{"id":"search","menu":true},{"id":"watchlist","menu":true},""" +
                        """{"id":"continueWatching"},{"id":"library:m","menu":true},{"id":"library:s","menu":true},""" +
                        """{"id":"requests","menu":true},{"id":"dashboard","menu":true},{"id":"downloads","menu":true}]""",
                ),
            )!!

        val applied = SideMenuLayout.apply(fresh, remote)

        assertEquals(listOf(lists, search, watchlist, cw, movies, shows, requests, dashboard, downloads), applied.rows)
        val menu = SideMenu.items(applied.rows, listOf(JellyfinLibrary("m", "Movies"), JellyfinLibrary("s", "Shows")), showLists = true)
        assertEquals(SideMenuItem.Home, menu.first())
        assertEquals(SideMenuItem.Settings, menu.last())
        assertEquals(listOf("home", "lists", "search", "watchlist"), menu.take(4).map { it.key })
    }

    @Test
    fun `S2 a remote watchlist with menu off hides only the Watchlist`() {
        val remote = SideMenuLayout.project(fresh, showLists = true).map { if (it.id == "watchlist") it.copy(menu = false) else it }

        val applied = SideMenuLayout.apply(fresh, remote)

        assertEquals(fresh.map { if (it.kind == HomeRowConfig.Kind.WATCHLIST) it.copy(showInSideMenu = false) else it }, applied.rows)
        assertEquals(true, applied.showLists)
        assertFalse(SideMenuItem.Watchlist in SideMenu.items(applied.rows, emptyList(), showLists = true))
    }

    @Test
    fun `S2 a value naming only the Watchlist leaves everything else where it is`() {
        val applied = SideMenuLayout.apply(fresh, listOf(Entry("watchlist", false)))

        assertEquals(ids(fresh), ids(applied.rows))
        assertEquals(listOf("menu:watchlist"), applied.rows.filter { !it.showInSideMenu }.map { it.id })
        assertNull("Lists' switch is not named", applied.showLists)
    }

    @Test
    fun `S3 entries this device lacks keep their remote place when it writes`() {
        val remote =
            Json.parseToJsonElement(
                """[{"id":"search","menu":true},{"id":"watchlist","menu":true},{"id":"fintv","menu":false},""" +
                    """{"id":"hero"},{"id":"continueWatching"},{"id":"library:m","menu":true}]""",
            )

        val written = SideMenuLayout.write(listOf(search, watchlist, cw, movies, lists, downloads), showLists = true, remote = remote)

        assertEquals(
            listOf("search", "watchlist", "fintv", "hero", "continueWatching", "library:m", "lists", "downloads"),
            layoutIds(written),
        )
        assertEquals(2, layoutIds(written).indexOf("fintv"))
        assertEquals(JsonPrimitive(false), written[2].jsonObject["menu"])
    }

    @Test
    fun `S4 a newer homeRows order decides the rows, menu-only entries keep sideMenu's positions`() {
        // sideMenu (t=400): B, X, A, Y. homeRows (t=500): A, B.
        val local = listOf(search, movies, watchlist, shows)
        val sideMenu = listOf(Entry("library:s", true), Entry("search", true), Entry("library:m", true), Entry("watchlist", true))
        val homeRows = listOf(HomeRowsLayout.Entry("library:m", true), HomeRowsLayout.Entry("library:s", true))
        val expected = listOf(movies, search, shows, watchlist)

        // homeRows applied first, then sideMenu, which is older than the rows' order.
        val homeFirst = SideMenuLayout.apply(HomeRowsLayout.apply(local, homeRows), sideMenu, keepRowOrder = true).rows
        assertEquals(expected, homeFirst)

        // sideMenu applied first, then the newer homeRows.
        val menuFirst = HomeRowsLayout.apply(SideMenuLayout.apply(local, sideMenu).rows, homeRows)
        assertEquals(expected, menuFirst)
    }

    @Test
    fun `an older homeRows value sets Home visibility but not the order`() {
        val local = listOf(search, movies, watchlist, shows)
        val homeRows = listOf(HomeRowsLayout.Entry("library:s", false), HomeRowsLayout.Entry("library:m", true))

        val applied = HomeRowsLayout.apply(local, homeRows, takeOrder = false)

        assertEquals(listOf(search, movies, watchlist, shows.copy(isEnabled = false)), applied)
    }

    @Test
    fun `S5 sideMenu never changes Home visibility, with or without a menu flag on a row`() {
        val local = listOf(search, cw.copy(isEnabled = false), movies.copy(isEnabled = false), list)
        val remote =
            SideMenuLayout.entries(
                Json.parseToJsonElement(
                    """[{"id":"continueWatching"},{"id":"list:p1","menu":false},{"id":"library:m","menu":false},{"id":"search"}]""",
                ),
            )!!

        val applied = SideMenuLayout.apply(local, remote).rows

        assertEquals(listOf(cw.copy(isEnabled = false), list, movies.copy(isEnabled = false, showInSideMenu = false), search), applied)
    }

    @Test
    fun `S6 an unknown id is ignored here and carried through on write`() {
        val raw =
            Json.parseToJsonElement(
                """[{"id":"search","menu":true},{"id":"podcasts","menu":false,"extra":7},{"id":"watchlist","menu":true}]""",
            )

        assertEquals(listOf(search, watchlist, cw), SideMenuLayout.apply(listOf(search, watchlist, cw), SideMenuLayout.entries(raw)!!).rows)

        val written = SideMenuLayout.write(listOf(search, watchlist, cw), showLists = true, remote = raw)
        assertEquals(listOf("search", "podcasts", "watchlist", "continueWatching"), layoutIds(written))
        assertEquals(JsonPrimitive(7), written[1].jsonObject["extra"])
    }

    @Test
    fun `Lists' menu flag is the showLists switch both ways`() {
        val applied = SideMenuLayout.apply(fresh, listOf(Entry("lists", false)))
        assertEquals(false, applied.showLists)
        assertEquals("the Lists row's own flag is not the switch", fresh, applied.rows)

        val written = SideMenuLayout.write(fresh, showLists = false, remote = null)
        assertEquals(JsonPrimitive(false), written.first { it.jsonObject["id"]!!.jsonPrimitive.content == "lists" }.jsonObject["menu"])
    }

    @Test
    fun `writing over nothing is this device's combined order and a round trip keeps it`() {
        val local = listOf(lists, cw, movies.copy(showInSideMenu = false), search, watchlist, requests, dashboard, downloads)
        val written = SideMenuLayout.write(local, showLists = true, remote = null)

        assertEquals(
            Json.parseToJsonElement(
                """[{"id":"lists","menu":true},{"id":"continueWatching"},{"id":"library:m","menu":false},{"id":"search","menu":true},""" +
                    """{"id":"watchlist","menu":true},{"id":"requests","menu":true},{"id":"dashboard","menu":true},{"id":"downloads","menu":true}]""",
            ),
            written,
        )
        assertEquals(local, SideMenuLayout.apply(fresh, SideMenuLayout.entries(written)!!).rows.filter { it.id != "s" })
    }

    @Test
    fun `a sideMenu value is an array of a string id with an optional bool menu`() {
        assertTrue(SideMenuLayout.decode(Json.parseToJsonElement("""[{"id":"search","menu":true},{"id":"hero"}]""")) != null)
        assertTrue(SettingsSyncSchema.isValid("sideMenu", Json.parseToJsonElement("""[{"id":"search","menu":false}]""")))
        assertNull(SideMenuLayout.decode(Json.parseToJsonElement("""[{"id":"search","menu":"false"}]""")))
        assertNull(SideMenuLayout.decode(Json.parseToJsonElement("""[{"menu":true}]""")))
        assertNull(SideMenuLayout.decode(Json.parseToJsonElement("""{"id":"search"}""")))
        assertFalse(SettingsSyncSchema.isValid("sideMenu", Json.parseToJsonElement("""["search"]""")))
    }

    @Test
    fun `moving any entry or flipping a menu switch is a sideMenu edit, entries appearing are not`() {
        val before = fresh

        assertTrue("a menu-only move", SideMenuLayout.isUserChange(before, listOf(watchlist, search) + fresh.drop(2)))
        assertTrue(
            "a row move",
            SideMenuLayout.isUserChange(
                before,
                fresh.map {
                    if (it ==
                        movies
                    ) {
                        shows
                    } else if (it == shows) {
                        movies
                    } else {
                        it
                    }
                },
            ),
        )
        assertTrue(
            "a menu switch",
            SideMenuLayout.isUserChange(
                before,
                fresh.map {
                    if (it ==
                        dashboard
                    ) {
                        it.copy(showInSideMenu = false)
                    } else {
                        it
                    }
                },
            ),
        )
        assertFalse("a Home switch", SideMenuLayout.isUserChange(before, fresh.map { if (it == cw) it.copy(isEnabled = false) else it }))
        assertFalse("a new library", SideMenuLayout.isUserChange(before, fresh + HomeRowConfig.library("a", "Anime")))
        assertFalse("a list subscribed", SideMenuLayout.isUserChange(before, listOf(search, watchlist, cw, list) + fresh.drop(3)))
        assertFalse("the first save", SideMenuLayout.isUserChange(null, fresh))
    }

    @Test
    fun `the migration adding Search is not an edit`() {
        val old = listOf(cw, requests, movies, shows, lists, dashboard, watchlist)

        assertFalse(SideMenuLayout.isUserChange(old, HomeRowSettings.normalized(old)))
    }

    @Test
    fun `seeding counts a hidden entry or a moved one, not an order grown by default`() {
        assertFalse("fresh", SideMenuLayout.isCustomised(fresh, showLists = true))
        assertFalse(
            "libraries only reordered",
            SideMenuLayout.isCustomised(
                fresh.map {
                    if (it ==
                        movies
                    ) {
                        shows
                    } else if (it == shows) {
                        movies
                    } else {
                        it
                    }
                },
                true,
            ),
        )
        assertFalse("rows moved", SideMenuLayout.isCustomised(listOf(search, watchlist, movies, list, shows, cw) + fresh.drop(5), true))
        val oldDefault = HomeRowSettings.normalized(listOf(watchlist, cw, requests, movies, shows, lists, dashboard))
        assertFalse("an untouched order from before Search", SideMenuLayout.isCustomised(oldDefault, true))

        assertTrue("Lists switched off", SideMenuLayout.isCustomised(fresh, showLists = false))
        assertTrue(
            "a library hidden",
            SideMenuLayout.isCustomised(
                fresh.map {
                    if (it ==
                        movies
                    ) {
                        it.copy(showInSideMenu = false)
                    } else {
                        it
                    }
                },
                true,
            ),
        )
        assertTrue("Downloads moved up", SideMenuLayout.isCustomised(listOf(search, downloads) + fresh.drop(1).dropLast(1), true))
        assertTrue("Lists above Search", SideMenuLayout.isCustomised(listOf(lists) + fresh.filter { it != lists }, true))
    }
}
