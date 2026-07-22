package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [HomeRowStore]; `saved == null` models a first launch. */
private class FakeHomeRowStore(var saved: List<HomeRowConfig>? = null) : HomeRowStore {
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

        assertFalse(settings.rows.value.first { it.id == "L1" }.isEnabled)
    }

    @Test
    fun `toggleRow flips visibility`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        assertTrue(settings.rows.value.first().isEnabled)
        settings.toggleRow(HomeRowConfig.CONTINUE_WATCHING_ID)
        assertFalse(settings.rows.value.first().isEnabled)
    }

    @Test
    fun `moveRow reorders and out-of-range is a no-op`() {
        val settings = HomeRowSettings(FakeHomeRowStore(saved = null))
        settings.updateLibraries(listOf(library("L1", "Movies"), library("L2", "Shows")))

        settings.moveRow(fromIndex = 2, toIndex = 0)
        assertEquals("L2", settings.rows.value.first().id)

        val before = settings.rows.value
        settings.moveRow(fromIndex = 9, toIndex = 0)
        assertEquals(before, settings.rows.value)
    }
}
