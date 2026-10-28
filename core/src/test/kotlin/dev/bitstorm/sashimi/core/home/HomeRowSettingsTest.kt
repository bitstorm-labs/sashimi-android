package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [HomeRowStore]; `saved == null` models a first launch. */
private class FakeHomeRowStore(
    var saved: List<HomeRowConfig>? = null,
) : HomeRowStore {
    override fun load(): List<HomeRowConfig>? = saved

    override fun save(rows: List<HomeRowConfig>) {
        saved = rows
    }
}

private fun library(
    id: String,
    name: String,
) = JellyfinLibrary(id = id, name = name, collectionType = "movies")

class HomeRowSettingsTest {
    @Test
    fun `first launch defaults to continue watching only`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        val rows = settings.rows.value
        assertEquals(1, rows.size)
        assertEquals(HomeRowConfig.Kind.CONTINUE_WATCHING, rows.first().kind)
        assertEquals(HomeRowConfig.CONTINUE_WATCHING_ID, rows.first().id)
    }

    @Test
    fun `persisted rows are restored verbatim`() {
        val store =
            FakeHomeRowStore(
                saved =
                    listOf(
                        HomeRowConfig.library(id = "L1", name = "Movies", isEnabled = false),
                        HomeRowConfig.continueWatching(),
                    ),
            )
        val settings = HomeRowSettings(store)
        val rows = settings.rows.value
        assertEquals(2, rows.size)
        assertEquals("L1", rows.first().id)
        assertFalse(rows.first().isEnabled)
    }

    @Test
    fun `updateLibraries appends new libraries and persists`() {
        val store = FakeHomeRowStore(saved = null)
        val settings = HomeRowSettings(store)

        settings.updateLibraries(listOf(library("L1", "Movies"), library("L2", "Shows")))

        val rows = settings.rows.value
        assertEquals(3, rows.size)
        assertEquals(HomeRowConfig.CONTINUE_WATCHING_ID, rows[0].id)
        assertEquals("L1", rows[1].id)
        assertEquals("L2", rows[2].id)
        assertEquals(rows, store.saved)
    }

    @Test
    fun `updateLibraries drops libraries that disappear but keeps order`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        settings.updateLibraries(listOf(library("L1", "Movies"), library("L2", "Shows")))

        settings.updateLibraries(listOf(library("L2", "Shows")))

        val ids = settings.rows.value.map { it.id }
        assertEquals(listOf(HomeRowConfig.CONTINUE_WATCHING_ID, "L2"), ids)
    }

    @Test
    fun `updateLibraries preserves visibility of existing rows`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        settings.updateLibraries(listOf(library("L1", "Movies")))
        settings.toggleRow("L1")

        // Re-running with the same library must not re-enable it.
        settings.updateLibraries(listOf(library("L1", "Movies")))

        assertFalse(
            settings.rows.value
                .first { it.id == "L1" }
                .isEnabled,
        )
    }

    @Test
    fun `toggleRow flips visibility`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        assertTrue(
            settings.rows.value
                .first()
                .isEnabled,
        )
        settings.toggleRow(HomeRowConfig.CONTINUE_WATCHING_ID)
        assertFalse(
            settings.rows.value
                .first()
                .isEnabled,
        )
    }

    @Test
    fun `moveRow reorders and out-of-range is a no-op`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        settings.updateLibraries(listOf(library("L1", "Movies"), library("L2", "Shows")))

        settings.moveRow(fromIndex = 2, toIndex = 0)
        assertEquals(
            "L2",
            settings.rows.value
                .first()
                .id,
        )

        val before = settings.rows.value
        settings.moveRow(fromIndex = 9, toIndex = 0)
        assertEquals(before, settings.rows.value)
    }
}

class HomeRowSideMenuToggleTest {
    @Test
    fun `toggling the side menu flips only that library row and persists`() {
        val store = FakeHomeRowStore()
        val settings = HomeRowSettings(store)
        settings.updateLibraries(listOf(library("a", "A"), library("b", "B")))

        settings.toggleSideMenu("a")
        settings.toggleSideMenu(HomeRowConfig.CONTINUE_WATCHING_ID)

        val rows = store.saved!!.associateBy { it.id }
        assertFalse(rows.getValue("a").showInSideMenu)
        assertTrue(rows.getValue("a").isEnabled)
        assertTrue(rows.getValue("b").showInSideMenu)
        assertTrue(rows.getValue(HomeRowConfig.CONTINUE_WATCHING_ID).showInSideMenu)
    }

    @Test
    fun `a new subscription's row goes right after Continue Watching, in subscription order`() {
        val store =
            FakeHomeRowStore(
                listOf(HomeRowConfig.library("lib1", "Movies"), HomeRowConfig.continueWatching(), HomeRowConfig.library("lib2", "TV")),
            )
        val settings = HomeRowSettings(store)

        settings.updateListRows(listOf("a" to "From Marge: Xmas", "b" to "Mine"))

        assertEquals(listOf("lib1", "continue_watching", "list:a", "list:b", "lib2"), settings.rows.value.map { it.id })
        assertEquals("From Marge: Xmas", settings.rows.value[2].displayName)
        assertEquals(settings.rows.value, store.saved)
    }

    @Test
    fun `a list row the user moved stays put, keeps its visibility, and takes the new title`() {
        val moved = HomeRowConfig.list("a", "Old").copy(isEnabled = false)
        val store = FakeHomeRowStore(listOf(HomeRowConfig.continueWatching(), HomeRowConfig.library("lib1", "Movies"), moved))
        val settings = HomeRowSettings(store)

        settings.updateListRows(listOf("a" to "From Marge: Renamed"))

        val row = settings.rows.value.last()
        assertEquals("list:a", row.id)
        assertFalse(row.isEnabled)
        assertEquals("From Marge: Renamed", row.displayName)
        assertFalse("a list row is not a side-menu destination", row.isSideMenuDestination)
    }

    @Test
    fun `unsubscribed lists lose their rows and library rows are untouched`() {
        val store =
            FakeHomeRowStore(
                listOf(HomeRowConfig.continueWatching(), HomeRowConfig.list("a", "A"), HomeRowConfig.library("lib1", "Movies")),
            )
        val settings = HomeRowSettings(store)

        settings.updateListRows(emptyList())
        settings.updateLibraries(listOf(library("lib1", "Movies")))

        assertEquals(listOf("continue_watching", "lib1"), settings.rows.value.map { it.id })
    }
}
