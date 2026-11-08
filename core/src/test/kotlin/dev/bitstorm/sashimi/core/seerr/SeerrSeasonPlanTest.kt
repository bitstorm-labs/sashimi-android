package dev.bitstorm.sashimi.core.seerr

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of sashimi-apple SeerrSeasonPlanTests: which seasons start ticked, which can't be picked. */
class SeerrSeasonPlanTest {
    private val now = SeerrDates.parse("2026-11-06T12:00:00.000Z")!!

    private fun details(
        seasons: List<Triple<Int, Int, String?>>,
        statuses: Map<Int, Int> = emptyMap(),
        requests: List<SeerrRequest>? = null,
    ): SeerrTitleDetails {
        val seasonJson =
            seasons.joinToString(
                ",",
            ) { (n, e, air) -> """{"seasonNumber":$n,"episodeCount":$e,"airDate":${air?.let { "\"$it\"" } ?: "null"}}""" }
        val statusJson = statuses.entries.joinToString(",") { """{"seasonNumber":${it.key},"status":${it.value}}""" }
        val decoded =
            SeerrTitleDetails.parse(
                """{"id":1,"name":"Love Is Blind","seasons":[$seasonJson],"mediaInfo":{"status":4,"seasons":[$statusJson]}}""",
            )
        return decoded.copy(mediaInfo = decoded.mediaInfo?.copy(requests = requests))
    }

    private fun loveIsBlind(): SeerrTitleDetails {
        val seasons = mutableListOf(Triple<Int, Int, String?>(0, 3, "2020-01-01"))
        seasons += (1..9).map { Triple(it, 10, "2020-02-13") }
        seasons += listOf(Triple(10, 12, "2026-03-01"), Triple(11, 10, "2026-10-01"))
        return details(seasons, (1..9).associateWith { 5 })
    }

    @Test
    fun `defaults to every season not on the server and never specials`() {
        val plan = SeerrSeasonPlan.of(loveIsBlind(), now)
        assertEquals(setOf(10, 11), plan.selected)
        assertEquals(listOf(10, 11), plan.missing)
        assertFalse(plan.seasons.any { it.number == 0 })
        assertEquals(listOf(10, 11), plan.requestSeasons)
        assertEquals("Request 2 seasons", plan.buttonTitle)
        assertTrue(plan.allMissingSelected)
    }

    @Test
    fun `seasons on the server collapse into one row that can't be picked`() {
        val plan = SeerrSeasonPlan.of(loveIsBlind(), now)
        assertEquals(listOf("Seasons 1–9", "Season 10", "Season 11"), plan.rows.map { it.title })
        assertFalse(plan.rows.first().isPickable)
        assertEquals("On server", plan.rows.first().detail)
        assertEquals("12 episodes", plan.rows[1].detail)
    }

    @Test
    fun `toggling and all missing`() {
        var plan = SeerrSeasonPlan.of(loveIsBlind(), now)
        plan = plan.toggle(11)
        assertEquals(setOf(10), plan.selected)
        assertFalse(plan.allMissingSelected)
        assertEquals("Request 1 season", plan.buttonTitle)
        plan = plan.toggle(5)
        assertEquals("a season on the server can't be ticked", setOf(10), plan.selected)
        plan = plan.setAllMissing(true)
        assertEquals(setOf(10, 11), plan.selected)
        plan = plan.setAllMissing(false)
        assertTrue(plan.selected.isEmpty())
        assertTrue(plan.requestSeasons.isEmpty())
    }

    @Test
    fun `unaired seasons can be picked but start unticked`() {
        val plan =
            SeerrSeasonPlan.of(details(listOf(Triple(1, 6, "2024-11-17"), Triple(2, 0, null), Triple(3, 8, "2027-05-01"))), now)
        assertEquals(setOf(1), plan.selected)
        assertEquals(listOf("6 episodes", "Not aired", "Not aired"), plan.rows.map { it.detail })
        assertTrue(plan.rows[1].isPickable)
        assertEquals("Request 1 season", plan.buttonTitle)
    }

    @Test
    fun `seasons already requested can't be picked again, declined ones can`() {
        val pending = SeerrRequest(id = 9, status = 1, seasons = listOf(SeerrSeasonRequest(2, 1)))
        val declined = SeerrRequest(id = 10, status = 3, seasons = listOf(SeerrSeasonRequest(3, 3)))
        val plan =
            SeerrSeasonPlan.of(
                details(
                    listOf(Triple(1, 6, "2020-01-01"), Triple(2, 6, "2021-01-01"), Triple(3, 6, "2022-01-01"), Triple(4, 6, "2023-01-01")),
                    statuses = mapOf(4 to 3),
                    requests = listOf(pending, declined),
                ),
                now,
            )
        assertEquals(setOf(1, 3), plan.selected)
        assertEquals(listOf("6 episodes", "Requested", "6 episodes", "Requested"), plan.rows.map { it.detail })
        assertFalse(plan.rows[1].isPickable)
    }

    @Test
    fun `without partial requests every missing season goes together`() {
        val plan = SeerrSeasonPlan.of(loveIsBlind(), now, partialRequestsEnabled = false).toggle(10)
        assertEquals(setOf(10, 11), plan.selected)
        assertFalse(plan.rows[1].isPickable)
    }

    @Test
    fun `a show with everything on the server has nothing missing`() {
        val plan = SeerrSeasonPlan.of(details(listOf(Triple(1, 6, "2020-01-01")), mapOf(1 to 5)), now)
        assertFalse(plan.hasMissingSeasons)
        assertTrue(plan.selected.isEmpty())
    }
}

/** Port of sashimi-apple SeerrSearchMergeTests: the "Not in your library" row lists only what isn't there. */
class SeerrSearchMergeTest {
    private var next = 0

    private fun libraryItem(
        name: String,
        type: ItemType,
        tmdb: String?,
        year: Int? = null,
    ) = BaseItemDto(
        id = "jf-${next++}",
        name = name,
        type = type,
        productionYear = year,
        providerIds = tmdb?.let { mapOf("Tmdb" to it, "Imdb" to "tt1") },
    )

    private fun result(
        id: Int,
        kind: SeerrMediaKind,
        title: String,
        date: String? = null,
        status: Int? = null,
    ) = SeerrSearchResult(id, kind, title, date = date, mediaInfo = status?.let { SeerrMediaInfo(status = it) })

    @Test
    fun `library items read their tmdb id`() {
        assertEquals(438631, libraryItem("Dune", ItemType.MOVIE, "438631").tmdbId)
        assertNull(libraryItem("Home video", ItemType.MOVIE, null).tmdbId)
    }

    @Test
    fun `the ProviderIds field decodes from Jellyfin's JSON`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val item = json.decodeFromString<BaseItemDto>("""{"Id":"a","Name":"Dune","ProviderIds":{"Tmdb":"438631","Imdb":"tt1"}}""")
        assertEquals(438631, item.tmdbId)
    }

    @Test
    fun `drops what the server has and what the library row already shows`() {
        val library = listOf(libraryItem("Dune", ItemType.MOVIE, "438631"), libraryItem("Dune: Part Two", ItemType.MOVIE, "693134"))
        val results =
            listOf(
                result(438631, SeerrMediaKind.MOVIE, "Dune"),
                result(693134, SeerrMediaKind.MOVIE, "Dune: Part Two", status = 5),
                result(90228, SeerrMediaKind.TV, "Dune: Prophecy"),
                result(841, SeerrMediaKind.MOVIE, "Dune", date = "1984-12-14", status = 2),
                result(1, SeerrMediaKind.MOVIE, "Already on Plex", status = 5),
            )
        val row = SeerrSearchMerge.requestable(results, library)
        assertEquals(listOf("Dune: Prophecy", "Dune"), row.map { it.title })
        assertEquals(1984, row.last().year)
    }

    @Test
    fun `a movie and a show with the same tmdb number are different titles`() {
        val row =
            SeerrSearchMerge.requestable(
                listOf(result(100, SeerrMediaKind.TV, "Some Show")),
                listOf(libraryItem("Some Movie", ItemType.MOVIE, "100")),
            )
        assertEquals(listOf("Some Show"), row.map { it.title })
    }

    @Test
    fun `library items without provider ids match on title and year`() {
        val row =
            SeerrSearchMerge.requestable(
                listOf(result(438631, SeerrMediaKind.MOVIE, "Dune", "2021-09-15"), result(841, SeerrMediaKind.MOVIE, "Dune", "1984-12-14")),
                listOf(libraryItem("Dune", ItemType.MOVIE, null, 2021)),
            )
        assertEquals(listOf(1984), row.map { it.year })
    }

    @Test
    fun `duplicates from Seerr appear once`() {
        assertEquals(
            1,
            SeerrSearchMerge
                .requestable(
                    listOf(result(5, SeerrMediaKind.MOVIE, "A"), result(5, SeerrMediaKind.MOVIE, "A")),
                    emptyList(),
                ).size,
        )
    }

    @Test
    fun `library match finds the title by tmdb id and kind`() {
        val show = libraryItem("Dune: Prophecy", ItemType.SERIES, "90228")
        val movie = libraryItem("Dune: Prophecy", ItemType.MOVIE, "90228")
        assertEquals(show.id, SeerrSearchMerge.libraryMatch(listOf(movie, show), 90228, SeerrMediaKind.TV)?.id)
        assertNull(SeerrSearchMerge.libraryMatch(listOf(movie), 1, SeerrMediaKind.MOVIE))
    }
}
