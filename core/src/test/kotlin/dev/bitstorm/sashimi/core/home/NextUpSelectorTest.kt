package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun episode(
    id: String,
    seriesId: String,
    played: Boolean = false,
) = BaseItemDto(
    id = id,
    name = id,
    type = ItemType.EPISODE,
    seriesId = seriesId,
    userData = UserItemDataDto(played = played),
)

class NextUpSelectorTest {
    @Test
    fun `fromNextUp picks the entry for this series`() {
        val nextUp =
            listOf(
                episode("e1", seriesId = "other"),
                episode("e2", seriesId = "target"),
                episode("e3", seriesId = "target"),
            )
        assertEquals("e2", NextUpSelector.fromNextUp(nextUp, "target")?.id)
    }

    @Test
    fun `fromNextUp returns null when no match`() {
        assertNull(NextUpSelector.fromNextUp(listOf(episode("e1", "other")), "target"))
    }

    @Test
    fun `firstUnwatched skips played episodes`() {
        val episodes =
            listOf(
                episode("e1", "s", played = true),
                episode("e2", "s", played = true),
                episode("e3", "s", played = false),
                episode("e4", "s", played = false),
            )
        assertEquals("e3", NextUpSelector.firstUnwatched(episodes)?.id)
    }

    @Test
    fun `firstUnwatched null when all played`() {
        val episodes = listOf(episode("e1", "s", played = true))
        assertNull(NextUpSelector.firstUnwatched(episodes))
    }
}
