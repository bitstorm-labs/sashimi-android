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
    season: Int? = null,
) = BaseItemDto(
    id = id,
    name = id,
    type = ItemType.EPISODE,
    seriesId = seriesId,
    userData = UserItemDataDto(played = played),
    parentIndexNumber = season,
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

    @Test
    fun `specialsLast moves season 0 behind the regular seasons`() {
        val seasons =
            listOf(0, 1, 2).map { BaseItemDto(id = "s$it", name = "s$it", type = ItemType.SEASON, indexNumber = it) }
        assertEquals(listOf("s1", "s2", "s0"), NextUpSelector.specialsLast(seasons).map { it.id })
    }

    @Test
    fun `firstRegular skips specials for a never-started show`() {
        val eps = listOf(episode("sp1", "t", season = 0), episode("e1", "t", season = 1), episode("e2", "t", season = 1))
        assertEquals("e1", NextUpSelector.firstRegular(eps)?.id)
    }

    @Test
    fun `firstRegular picks the first unwatched regular episode`() {
        val eps = listOf(episode("sp1", "t", season = 0), episode("e1", "t", played = true, season = 1), episode("e2", "t", season = 1))
        assertEquals("e2", NextUpSelector.firstRegular(eps)?.id)
    }

    @Test
    fun `firstRegular restarts a fully watched show at its first regular episode`() {
        val eps = listOf(episode("sp1", "t", season = 0), episode("e1", "t", played = true, season = 1))
        assertEquals("e1", NextUpSelector.firstRegular(eps)?.id)
    }

    @Test
    fun `only season 0 episodes are specials`() {
        assertEquals(true, episode("sp", "t", season = 0).isSpecial)
        assertEquals(false, episode("e", "t", season = 1).isSpecial)
    }
}
