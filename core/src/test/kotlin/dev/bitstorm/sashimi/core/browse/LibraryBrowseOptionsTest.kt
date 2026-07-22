package dev.bitstorm.sashimi.core.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryBrowseOptionsTest {
    @Test
    fun `sort wire values match Jellyfin params`() {
        assertEquals("SortName", LibrarySort.NAME.wire)
        assertEquals("DateCreated", LibrarySort.DATE_ADDED.wire)
        assertEquals("PremiereDate", LibrarySort.RELEASE_DATE.wire)
        assertEquals("CommunityRating", LibrarySort.RATING.wire)
        assertEquals("Runtime", LibrarySort.RUNTIME.wire)
    }

    @Test
    fun `filter maps to isPlayed`() {
        assertNull(LibraryFilter.ALL.isPlayed)
        assertEquals(false, LibraryFilter.UNWATCHED.isPlayed)
        assertEquals(true, LibraryFilter.WATCHED.isPlayed)
        assertNull(LibraryFilter.FAVORITES.isPlayed)
    }

    @Test
    fun `filter maps to isFavorite`() {
        assertNull(LibraryFilter.ALL.isFavorite)
        assertNull(LibraryFilter.UNWATCHED.isFavorite)
        assertEquals(true, LibraryFilter.FAVORITES.isFavorite)
    }

    @Test
    fun `sort direction toggles and maps to wire`() {
        assertEquals("Ascending", SortDirection.ASCENDING.wire)
        assertEquals("Descending", SortDirection.DESCENDING.wire)
        assertEquals(SortDirection.DESCENDING, SortDirection.ASCENDING.toggled())
        assertEquals(SortDirection.ASCENDING, SortDirection.DESCENDING.toggled())
    }
}
