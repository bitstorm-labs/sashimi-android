package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun ep(
    id: String,
    seasonId: String? = null,
) = BaseItemDto(id = id, name = id, type = ItemType.EPISODE, seasonId = seasonId)

private fun season(id: String) = BaseItemDto(id = id, name = id, type = ItemType.SEASON)

class AutoPlayNextResolverTest {
    @Test
    fun `nextInList returns following episode`() {
        val eps = listOf(ep("a"), ep("b"), ep("c"))
        assertEquals("b", AutoPlayNextResolver.nextInList(ep("a"), eps)?.id)
        assertEquals("c", AutoPlayNextResolver.nextInList(ep("b"), eps)?.id)
    }

    @Test
    fun `nextInList null on last episode`() {
        val eps = listOf(ep("a"), ep("b"))
        assertNull(AutoPlayNextResolver.nextInList(ep("b"), eps))
    }

    @Test
    fun `nextInList null when current missing`() {
        assertNull(AutoPlayNextResolver.nextInList(ep("z"), listOf(ep("a"), ep("b"))))
    }

    @Test
    fun `nextSeasonId rolls over to following season`() {
        val seasons = listOf(season("s1"), season("s2"), season("s3"))
        assertEquals("s2", AutoPlayNextResolver.nextSeasonId("s1", seasons))
        assertEquals("s3", AutoPlayNextResolver.nextSeasonId("s2", seasons))
    }

    @Test
    fun `nextSeasonId null on last season`() {
        val seasons = listOf(season("s1"), season("s2"))
        assertNull(AutoPlayNextResolver.nextSeasonId("s2", seasons))
    }

    @Test
    fun `nextSeasonId null when season unknown or null`() {
        val seasons = listOf(season("s1"))
        assertNull(AutoPlayNextResolver.nextSeasonId(null, seasons))
        assertNull(AutoPlayNextResolver.nextSeasonId("missing", seasons))
    }
}
