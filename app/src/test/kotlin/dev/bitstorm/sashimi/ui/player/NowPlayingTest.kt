package dev.bitstorm.sashimi.ui.player

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {
    private val art: (BaseItemDto) -> String? = { "https://jf/Items/${it.seriesId ?: it.id}/Images/Primary" }

    @Test
    fun `episode leads with its own name and carries series and code as subtitle`() {
        val item =
            BaseItemDto(
                id = "ep1",
                name = "Pilot",
                type = ItemType.EPISODE,
                seriesName = "Severance",
                seriesId = "series1",
                parentIndexNumber = 1,
                indexNumber = 2,
            )
        val now = NowPlaying.from(item, art)
        assertEquals("Pilot", now.title)
        assertEquals("Severance · S1:E2", now.subtitle)
        assertEquals("https://jf/Items/series1/Images/Primary", now.artworkUrl)
    }

    @Test
    fun `episode without numbers keeps just the series name`() {
        val item = BaseItemDto(id = "ep1", name = "Special", type = ItemType.EPISODE, seriesName = "Show")
        assertEquals("Show", NowPlaying.from(item, art).subtitle)
    }

    @Test
    fun `episode with no series name and no numbers has no subtitle`() {
        val item = BaseItemDto(id = "ep1", name = "Clip", type = ItemType.EPISODE)
        assertNull(NowPlaying.from(item, art).subtitle)
    }

    @Test
    fun `nameless episode falls back to the series name as title`() {
        val item = BaseItemDto(id = "ep1", name = "", type = ItemType.EPISODE, seriesName = "Show", parentIndexNumber = 3, indexNumber = 4)
        val now = NowPlaying.from(item, art)
        assertEquals("Show", now.title)
        assertEquals("Show · S3:E4", now.subtitle)
    }

    @Test
    fun `movie uses its year as subtitle`() {
        val item = BaseItemDto(id = "m1", name = "Heat", type = ItemType.MOVIE, productionYear = 1995)
        val now = NowPlaying.from(item, art)
        assertEquals("Heat", now.title)
        assertEquals("1995", now.subtitle)
        assertEquals("https://jf/Items/m1/Images/Primary", now.artworkUrl)
    }

    @Test
    fun `no artwork when no server url is known`() {
        val item = BaseItemDto(id = "m1", name = "Heat", type = ItemType.MOVIE)
        val now = NowPlaying.from(item) { null }
        assertNull(now.artworkUrl)
        assertNull(now.subtitle)
    }
}
