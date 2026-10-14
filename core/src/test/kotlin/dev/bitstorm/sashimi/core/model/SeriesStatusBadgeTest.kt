package dev.bitstorm.sashimi.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesStatusBadgeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun item(
        type: ItemType?,
        status: String?,
    ) = BaseItemDto(id = "x", type = type, status = status)

    @Test
    fun `continuing series is a Continuing badge`() {
        assertEquals(
            SeriesStatusBadge(SeriesStatusBadge.Kind.CONTINUING, "Continuing"),
            SeriesStatusBadge.from(item(ItemType.SERIES, "Continuing")),
        )
    }

    @Test
    fun `ended series is an Ended badge`() {
        assertEquals(
            SeriesStatusBadge(SeriesStatusBadge.Kind.ENDED, "Ended"),
            SeriesStatusBadge.from(item(ItemType.SERIES, "Ended")),
        )
    }

    @Test
    fun `unreleased series is labelled Upcoming`() {
        assertEquals(
            SeriesStatusBadge(SeriesStatusBadge.Kind.UPCOMING, "Upcoming"),
            SeriesStatusBadge.from(item(ItemType.SERIES, "Unreleased")),
        )
    }

    @Test
    fun `missing blank or unknown status gives no badge`() {
        assertNull(SeriesStatusBadge.from(item(ItemType.SERIES, null)))
        assertNull(SeriesStatusBadge.from(item(ItemType.SERIES, "")))
        assertNull(SeriesStatusBadge.from(item(ItemType.SERIES, "Cancelled")))
    }

    @Test
    fun `movies and untyped items never get a badge`() {
        for (type in listOf(ItemType.MOVIE, ItemType.VIDEO, null)) {
            assertNull("$type", SeriesStatusBadge.from(item(type, "Continuing"), seriesStatus = "Continuing"))
        }
    }

    @Test
    fun `episodes and seasons use the parent series status`() {
        for (type in listOf(ItemType.EPISODE, ItemType.SEASON)) {
            assertEquals(
                "$type",
                SeriesStatusBadge(SeriesStatusBadge.Kind.ENDED, "Ended"),
                SeriesStatusBadge.from(item(type, status = null), seriesStatus = "Ended"),
            )
        }
    }

    @Test
    fun `episodes and seasons ignore their own status and need the series one`() {
        for (type in listOf(ItemType.EPISODE, ItemType.SEASON)) {
            assertNull("$type", SeriesStatusBadge.from(item(type, "Continuing"), seriesStatus = null))
        }
    }

    @Test
    fun `a series ignores any passed series status and uses its own`() {
        assertEquals(
            SeriesStatusBadge.Kind.CONTINUING,
            SeriesStatusBadge.from(item(ItemType.SERIES, "Continuing"), seriesStatus = "Ended")?.kind,
        )
    }

    @Test
    fun `Status decodes from the server JSON`() {
        val dto =
            json.decodeFromString(
                BaseItemDto.serializer(),
                """{"Id":"s1","Name":"Severance","Type":"Series","Status":"Continuing"}""",
            )
        assertEquals("Continuing", dto.status)
        assertEquals(SeriesStatusBadge.Kind.CONTINUING, SeriesStatusBadge.from(dto)?.kind)
    }
}
