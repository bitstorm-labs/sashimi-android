package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerMediaGroupingTest {
    private fun item(
        id: String,
        name: String,
        type: ItemType = ItemType.MOVIE,
        year: Int? = 2020,
        width: Int? = null,
        rating: Double? = null,
    ) = BaseItemDto(
        id = id,
        name = name,
        type = type,
        productionYear = year,
        communityRating = rating,
        mediaStreams = width?.let { listOf(MediaStream(type = "Video", width = it, height = it * 9 / 16)) },
    )

    private fun on(
        server: String,
        item: BaseItemDto,
    ) = ServerMediaResult(item, serverId = server, serverName = server.uppercase())

    @Test
    fun `the same title on two servers is one group with both sources`() {
        val groups =
            ServerMediaGrouping.groups(
                listOf(on("b", item("b1", "Dune")), on("a", item("a1", "Dune"))),
                preferredServerId = "a",
            )
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().sources.map { it.serverId })
    }

    @Test
    fun `titles differing only by diacritics case or punctuation collapse`() {
        val groups =
            ServerMediaGrouping.groups(
                listOf(on("a", item("a1", "Amélie")), on("b", item("b1", "amelie")), on("c", item("c1", "Amelie!"))),
            )
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().sources.size)
    }

    @Test
    fun `a different year or type is a different title`() {
        val groups =
            ServerMediaGrouping.groups(
                listOf(
                    on("a", item("a1", "Dune", year = 1984)),
                    on("a", item("a2", "Dune", year = 2021)),
                    on("b", item("b1", "Dune", type = ItemType.SERIES, year = 2021)),
                ),
            )
        assertEquals(3, groups.size)
    }

    @Test
    fun `within one server the better copy is kept`() {
        val groups =
            ServerMediaGrouping.groups(
                listOf(on("a", item("hd", "Dune", width = 1920)), on("a", item("uhd", "Dune", width = 3840))),
            )
        assertEquals(listOf("uhd"), groups.single().sources.map { it.item.id })

        val byRating =
            ServerMediaGrouping.groups(
                listOf(on("a", item("low", "Dune", rating = 6.0)), on("a", item("high", "Dune", rating = 8.0))),
            )
        assertEquals("high", byRating.single().primary.item.id)
    }

    @Test
    fun `sources follow preferred server then saved server order`() {
        val sources =
            ServerMediaGrouping.orderSources(
                listOf(on("x", item("1", "T")), on("y", item("2", "T")), on("z", item("3", "T"))),
                preferredServerId = "z",
                serverOrder = listOf("y", "x", "z"),
            )
        assertEquals(listOf("z", "y", "x"), sources.map { it.serverId })
    }

    @Test
    fun `visible keeps movies and series and drops the originating title everywhere`() {
        val origin = item("o1", "Dune")
        val results =
            listOf(
                on("a", origin),
                on("b", item("b1", "Dune")),
                on("a", item("e1", "Pilot", type = ItemType.EPISODE)),
                on("a", item("s1", "Arrakis", type = ItemType.SERIES)),
                on("a", item("s1", "Arrakis", type = ItemType.SERIES)),
            )
        val visible =
            Filmography.visible(
                results,
                excludeTitleKey = ServerMediaGrouping.titleKey(origin),
                excludeItemId = "o1",
                excludeServerId = "a",
            )
        assertEquals(listOf("a:s1"), visible.map { it.id })
    }

    @Test
    fun `build is sorted by title and does not depend on which server answered first`() {
        val a = listOf(on("a", item("a1", "Zodiac")), on("a", item("a2", "Élan")), on("a", item("a3", "Alien")))
        val b = listOf(on("b", item("b1", "Elan", year = 2020)), on("b", item("b2", "Brazil")))
        val one = Filmography.build(a + b, preferredServerId = "a", serverOrder = listOf("a", "b"))
        val two = Filmography.build(b + a, preferredServerId = "a", serverOrder = listOf("a", "b"))
        assertEquals(listOf("Alien", "Brazil", "Élan", "Zodiac"), one.map { it.primary.item.name })
        assertEquals(one, two)
        assertEquals(listOf("a", "b"), one.first { it.primary.item.name == "Élan" }.sources.map { it.serverId })
    }
}
