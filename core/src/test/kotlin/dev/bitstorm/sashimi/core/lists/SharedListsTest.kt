package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedListsTest {
    private fun ep(
        id: String,
        series: String?,
        season: Int,
        episode: Int,
    ) = ListEntry(id, ItemType.EPISODE, series, season, episode)

    private fun mv(id: String) = ListEntry(id, ItemType.MOVIE, null, null, null)

    private fun sub(
        id: String,
        vararg seen: String,
    ) = ListSubscription(id, seen.toList())

    @Test
    fun `owner is the first editable share`() {
        assertEquals(
            "u2",
            SharedLists.owner(listOf(PlaylistShare("u1", false), PlaylistShare("u2", true), PlaylistShare("u3", true))),
        )
        assertNull(SharedLists.owner(emptyList()))
        assertNull(SharedLists.owner(listOf(PlaylistShare("u1", false))))
    }

    @Test
    fun `pilot shows as its series and repeats collapse`() {
        val shown =
            SharedLists.displayEntries(
                listOf(
                    ep("e1", "show1", 1, 1),
                    mv("m1"),
                    ep("e2", "show1", 1, 2),
                    mv("m1"),
                    ep("e3", "show2", 2, 1),
                    ep("e4", null, 1, 1),
                    ep("e5", "show1", 1, 1),
                ),
            )
        assertEquals(
            listOf(
                DisplayEntry("show1", true),
                DisplayEntry("m1", false),
                DisplayEntry("e2", false),
                DisplayEntry("e3", false),
                DisplayEntry("e4", false),
            ),
            shown,
        )
    }

    @Test
    fun `decode tolerates missing and corrupt values`() {
        listOf(null, "", "not json", "{}").forEach { assertEquals(emptyList<ListSubscription>(), SharedLists.decodeSubscriptions(it)) }
        val raw = """[{"id":"a","seen":["x"]},{"seen":[]},{"id":"a","seen":[]},{"id":"b"}]"""
        assertEquals(listOf(sub("a", "x"), sub("b")), SharedLists.decodeSubscriptions(raw))
    }

    @Test
    fun `encode round trips`() {
        val subs = listOf(sub("a", "x", "y"), sub("b"))
        assertEquals(subs, SharedLists.decodeSubscriptions(SharedLists.encodeSubscriptions(subs)))
    }

    @Test
    fun `new count ignores seen and unknown ids`() {
        assertEquals(2, SharedLists.newCount(listOf("a", "b", "c"), listOf("b")))
        assertEquals(0, SharedLists.newCount(listOf("a"), listOf("a", "z")))
    }

    @Test
    fun `subscription edits`() {
        assertEquals(listOf(sub("a"), sub("b", "x", "y")), SharedLists.subscribe(listOf(sub("a")), "b", listOf("x", "y")))
        assertEquals(listOf(sub("a", "q")), SharedLists.subscribe(listOf(sub("a", "q")), "a", listOf("x")))
        assertEquals(listOf(sub("b")), SharedLists.unsubscribe(listOf(sub("a"), sub("b")), "a"))
        assertEquals(listOf(sub("a", "x"), sub("b")), SharedLists.markSeen(listOf(sub("a", "q"), sub("b")), "a", listOf("x")))
        assertEquals(listOf(sub("b")), SharedLists.prune(listOf(sub("a"), sub("b"), sub("c")), listOf("a", "c")))
    }

    @Test
    fun `new row goes after continue watching`() {
        fun index(keys: List<String>) = SharedLists.insertionIndex(keys, "continueWatching", "hero")
        assertEquals(2, index(listOf("hero", "continueWatching", "lib1")))
        assertEquals(2, index(listOf("lib1", "hero")))
        assertEquals(0, index(listOf("lib1")))
    }

    @Test
    fun `a series is anchored on its first regular episode`() {
        fun e(
            id: String,
            season: Int?,
            episode: Int?,
        ) = BaseItemDto(id = id, type = ItemType.EPISODE, parentIndexNumber = season, indexNumber = episode)
        val episodes = listOf(e("special", 0, 1), e("s2e1", 2, 1), e("s1e3", 1, 3), e("s1e2", 1, 2), e("unnumbered", 1, null))
        assertEquals("s1e2", SharedLists.seriesAnchor(episodes)?.id)
        assertNull(SharedLists.seriesAnchor(listOf(e("special", 0, 1))))
    }
}
