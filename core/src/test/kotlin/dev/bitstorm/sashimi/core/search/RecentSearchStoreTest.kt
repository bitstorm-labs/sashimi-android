package dev.bitstorm.sashimi.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRecentSearchPersistence(var stored: List<String> = emptyList()) :
    RecentSearchPersistence {
    override fun load(): List<String> = stored

    override fun save(searches: List<String>) {
        stored = searches
    }
}

class RecentSearchStoreTest {
    @Test
    fun `add inserts newest first and persists`() {
        val persistence = FakeRecentSearchPersistence()
        val store = RecentSearchStore(persistence)
        store.add("alien")
        store.add("aliens")
        assertEquals(listOf("aliens", "alien"), store.searches.value)
        assertEquals(listOf("aliens", "alien"), persistence.stored)
    }

    @Test
    fun `add dedupes case-insensitively and moves match to front`() {
        val store = RecentSearchStore(FakeRecentSearchPersistence())
        store.add("Alien")
        store.add("Predator")
        store.add("alien")
        assertEquals(listOf("alien", "Predator"), store.searches.value)
    }

    @Test
    fun `add trims and ignores blanks`() {
        val store = RecentSearchStore(FakeRecentSearchPersistence())
        store.add("  Dune  ")
        store.add("   ")
        assertEquals(listOf("Dune"), store.searches.value)
    }

    @Test
    fun `add caps at ten`() {
        val store = RecentSearchStore(FakeRecentSearchPersistence())
        (1..12).forEach { store.add("q$it") }
        assertEquals(10, store.searches.value.size)
        assertEquals("q12", store.searches.value.first())
        assertEquals("q3", store.searches.value.last())
    }

    @Test
    fun `clear empties the list`() {
        val persistence = FakeRecentSearchPersistence()
        val store = RecentSearchStore(persistence)
        store.add("x")
        store.clear()
        assertTrue(store.searches.value.isEmpty())
        assertTrue(persistence.stored.isEmpty())
    }

    @Test
    fun `loads persisted searches on construction`() {
        val store = RecentSearchStore(FakeRecentSearchPersistence(listOf("a", "b")))
        assertEquals(listOf("a", "b"), store.searches.value)
    }
}
