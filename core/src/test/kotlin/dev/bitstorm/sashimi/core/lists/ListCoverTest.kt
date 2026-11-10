package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The list cover rule (#244): a poster stack of the first three different titles. */
class ListCoverTest {
    private fun movie(id: String) = BaseItemDto(id = id, name = id, type = ItemType.MOVIE, playlistItemId = "pe-$id")

    private fun episode(
        id: String,
        series: String,
        season: Int,
        number: Int,
    ) = BaseItemDto(
        id = id,
        name = id,
        type = ItemType.EPISODE,
        seriesId = series,
        seriesName = "Show $series",
        parentIndexNumber = season,
        indexNumber = number,
        playlistItemId = "pe-$id",
    )

    private fun cover(vararg entries: BaseItemDto) = SharedLists.cover(SharedListsService.cards(entries.toList()))

    private fun ListCover.ids() =
        when (this) {
            is ListCover.Stack -> posters.map { it.id }
            ListCover.Empty -> emptyList()
        }

    @Test
    fun `the first three different titles make the stack, front first`() {
        val c = cover(movie("m1"), movie("m2"), movie("m3"), movie("m4"), movie("m5"))
        assertEquals(listOf("m1", "m2", "m3"), c.ids())
    }

    @Test
    fun `a pilot counts as its series and a repeat counts once`() {
        val c = cover(episode("p", "show1", 1, 1), movie("m1"), movie("m1"), movie("m2"), episode("p2", "show1", 1, 1))
        assertEquals(listOf("show1", "m1", "m2"), c.ids())
    }

    @Test
    fun `episodes of a show already on the cover are the same title`() {
        // S1:E1 stands for show1; S2:E3 of show1 shows the same poster, so it is skipped.
        val c = cover(episode("p", "show1", 1, 1), episode("e", "show1", 2, 3), movie("m1"), movie("m2"))
        assertEquals(listOf("show1", "m1", "m2"), c.ids())
        val two = cover(episode("a", "show2", 2, 1), episode("b", "show2", 3, 1), movie("m1"))
        assertEquals(listOf("a", "m1"), two.ids())
    }

    @Test
    fun `one title is a single poster and two are a two-poster stack`() {
        assertEquals(listOf("only"), cover(movie("only")).ids())
        assertEquals(listOf("m1", "m2"), cover(movie("m1"), movie("m2"), movie("m1")).ids())
    }

    @Test
    fun `an empty list has no cover`() {
        assertEquals(ListCover.Empty, cover())
    }
}
