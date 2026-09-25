package dev.bitstorm.sashimi.ui.util

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailMetadataTest {
    private val threeHours = 3L * 3600 * 10_000_000

    @Test
    fun `movie leads with the release year, then the runtime`() {
        val movie = BaseItemDto(id = "m", type = ItemType.MOVIE, productionYear = 2023, runTimeTicks = threeHours)
        assertEquals(listOf("2023", "3h 0m"), Formatting.detailMetadata(movie))
    }

    @Test
    fun `movie shows the year, not the full date, even when it has a premiere date`() {
        val movie =
            BaseItemDto(
                id = "m",
                type = ItemType.MOVIE,
                productionYear = 2023,
                premiereDate = "2023-07-21T12:00:00.0000000Z",
                runTimeTicks = threeHours,
            )
        assertEquals(listOf("2023", "3h 0m"), Formatting.detailMetadata(movie))
    }

    @Test
    fun `movie without ProductionYear falls back to the PremiereDate year`() {
        val movie =
            BaseItemDto(
                id = "m",
                type = ItemType.MOVIE,
                premiereDate = "2023-07-21T12:00:00.0000000Z",
                runTimeTicks = threeHours,
            )
        assertEquals(listOf("2023", "3h 0m"), Formatting.detailMetadata(movie))
    }

    @Test
    fun `episode keeps its full air date`() {
        val episode =
            BaseItemDto(
                id = "e",
                type = ItemType.EPISODE,
                productionYear = 2024,
                premiereDate = "2024-11-08T12:00:00.0000000Z",
                runTimeTicks = 45L * 60 * 10_000_000,
            )
        assertEquals(listOf("November 8, 2024", "45 min"), Formatting.detailMetadata(episode))
    }

    @Test
    fun `movie with no year and no runtime shows nothing`() {
        assertEquals(emptyList<String>(), Formatting.detailMetadata(BaseItemDto(id = "m", type = ItemType.MOVIE)))
    }
}
