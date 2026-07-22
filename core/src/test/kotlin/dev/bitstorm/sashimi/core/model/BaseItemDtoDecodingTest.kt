package dev.bitstorm.sashimi.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseItemDtoDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes a sample episode with unknown fields ignored`() {
        // Includes ServerFieldWeDoNotModel to prove ignoreUnknownKeys is on.
        val sample =
            """
            {
              "Id": "abc123",
              "Name": "The One With The Test",
              "Type": "Episode",
              "SeriesName": "Friends",
              "SeriesId": "series-1",
              "IndexNumber": 4,
              "ParentIndexNumber": 2,
              "RunTimeTicks": 12000000000,
              "ProductionYear": 1995,
              "ServerFieldWeDoNotModel": {"nested": true},
              "UserData": {
                "PlaybackPositionTicks": 6000000000,
                "Played": false,
                "IsFavorite": true
              },
              "MediaStreams": [
                {"Type": "Video", "Width": 3840, "Height": 2160, "Codec": "hevc"},
                {"Type": "Audio", "Codec": "eac3", "Channels": 6}
              ]
            }
            """.trimIndent()

        val item = json.decodeFromString<BaseItemDto>(sample)

        assertEquals("abc123", item.id)
        assertEquals(ItemType.EPISODE, item.type)
        assertEquals("Friends", item.seriesName)
        // displayTitle formats series + SxEy for episodes.
        assertEquals("Friends S2E4", item.displayTitle)
        // progressPercent = 6e9 / 12e9 = 0.5
        assertEquals(0.5, item.progressPercent, 0.0001)
        // 3840x2160 → 4K badge.
        assertEquals("4K", item.qualityBadge)
        assertTrue(item.userData?.isFavorite == true)
        assertEquals(false, item.userData?.played)
    }

    @Test
    fun `unknown item type decodes to UNKNOWN not a throw`() {
        val sample = """{"Id":"x","Name":"Mystery","Type":"MusicVideo"}"""
        val item = json.decodeFromString<BaseItemDto>(sample)
        assertEquals(ItemType.UNKNOWN, item.type)
    }

    @Test
    fun `series with no media stream has null quality badge`() {
        val sample = """{"Id":"s","Name":"Show","Type":"Series"}"""
        val item = json.decodeFromString<BaseItemDto>(sample)
        assertNull(item.qualityBadge)
    }

    @Test
    fun `decodes items response wrapper`() {
        val sample =
            """{"Items":[{"Id":"1","Name":"A"},{"Id":"2","Name":"B"}],"TotalRecordCount":2}"""
        val response = json.decodeFromString<ItemsResponse>(sample)
        assertEquals(2, response.totalRecordCount)
        assertEquals(listOf("A", "B"), response.items.map { it.name })
    }
}
