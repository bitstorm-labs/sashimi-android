package dev.bitstorm.sashimi.core.seerr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Port of sashimi-apple SeerrStatusTests: pills, request states, the Home row rule. */
class SeerrStatusTest {
    private fun info(
        status: Int?,
        requests: List<SeerrRequest>? = null,
    ) = SeerrMediaInfo(status = status, requests = requests)

    private fun request(
        status: SeerrRequestStatus,
        by: Int = 7,
        media: SeerrMediaInfo? = null,
        kind: SeerrMediaKind = SeerrMediaKind.MOVIE,
        seasons: List<Int>? = null,
        created: String = "2026-11-06T09:00:00.000Z",
        updated: String? = null,
    ) = SeerrRequest(
        id = 1,
        status = status.raw,
        type = kind.wire,
        createdAt = created,
        updatedAt = updated ?: created,
        seasons = seasons?.map { SeerrSeasonRequest(it, status.raw) },
        media = media,
        requestedBy = SeerrRequestUser(id = by),
    )

    @Test
    fun `nothing known is requestable`() {
        assertEquals(SeerrBadge.REQUEST, SeerrBadge.of(null, 7))
        assertEquals(SeerrBadge.REQUEST, SeerrBadge.of(info(1), 7))
    }

    @Test
    fun `pending, processing and available map to their pills`() {
        assertEquals(SeerrBadge.PENDING, SeerrBadge.of(info(2), 7))
        assertEquals(SeerrBadge.COMING, SeerrBadge.of(info(3), 7))
        assertEquals(SeerrBadge.AVAILABLE, SeerrBadge.of(info(5), 7))
    }

    @Test
    fun `partially available can still be requested, blocklisted and deleted read as unknown`() {
        assertEquals(SeerrBadge.REQUEST, SeerrBadge.of(info(4), 7))
        assertEquals(SeerrBadge.REQUEST, SeerrBadge.of(info(6), 7))
        assertEquals(SeerrBadge.REQUEST, SeerrBadge.of(info(7), 7))
    }

    @Test
    fun `my declined request shows declined, someone else's does not stop me`() {
        assertEquals(SeerrBadge.DECLINED, SeerrBadge.of(info(1, listOf(request(SeerrRequestStatus.DECLINED))), 7))
        assertEquals(SeerrBadge.REQUEST, SeerrBadge.of(info(1, listOf(request(SeerrRequestStatus.DECLINED, by = 99))), 7))
    }

    @Test
    fun `pill labels match the mockups`() {
        assertEquals(
            listOf(
                "Request",
                "Pending",
                "Coming",
                "Declined",
            ),
            listOf(SeerrBadge.REQUEST, SeerrBadge.PENDING, SeerrBadge.COMING, SeerrBadge.DECLINED).map {
                it.label
            },
        )
    }

    @Test
    fun `request states`() {
        assertEquals(SeerrRequestState.Pending, SeerrRequestState.of(request(SeerrRequestStatus.PENDING, media = info(2))))
        assertEquals(SeerrRequestState.Declined, SeerrRequestState.of(request(SeerrRequestStatus.DECLINED, media = info(1))))
        assertEquals(SeerrRequestState.Coming(null), SeerrRequestState.of(request(SeerrRequestStatus.APPROVED, media = info(3))))
        assertEquals(SeerrRequestState.Ready, SeerrRequestState.of(request(SeerrRequestStatus.APPROVED, media = info(5))))
        assertEquals(SeerrRequestState.Ready, SeerrRequestState.of(request(SeerrRequestStatus.COMPLETED, media = info(5))))
        assertEquals(SeerrRequestState.Failed, SeerrRequestState.of(request(SeerrRequestStatus.FAILED, media = info(3))))
    }

    @Test
    fun `download progress adds up every download`() {
        val media = info(3).copy(downloadStatus = listOf(SeerrDownload(100.0, 50.0), SeerrDownload(300.0, 102.0)))
        val state = SeerrRequestState.of(request(SeerrRequestStatus.APPROVED, media = media)) as SeerrRequestState.Coming
        assertEquals(0.62, state.progress!!, 0.001)
    }

    @Test
    fun `a show is ready only when every requested season is on the server`() {
        val media = info(4).copy(seasons = listOf(SeerrSeasonStatus(1, 5), SeerrSeasonStatus(2, 3)))
        val both = request(SeerrRequestStatus.APPROVED, media = media, kind = SeerrMediaKind.TV, seasons = listOf(1, 2))
        val first = request(SeerrRequestStatus.APPROVED, media = media, kind = SeerrMediaKind.TV, seasons = listOf(1))
        assertEquals(SeerrRequestState.Coming(null), SeerrRequestState.of(both))
        assertEquals(SeerrRequestState.Ready, SeerrRequestState.of(first))
    }

    @Test
    fun `subtitles read like the mockup`() {
        val now = SeerrDates.parse("2026-11-06T18:00:00.000Z")!!
        val utc = ZoneOffset.UTC
        val downloading = info(3).copy(downloadStatus = listOf(SeerrDownload(100.0, 38.0)))
        val arrived = info(5).copy(mediaAddedAt = "2026-11-05T20:00:00.000Z")
        val pending = request(SeerrRequestStatus.PENDING, created = "2026-11-06T09:00:00.000Z")
        val approved =
            request(
                SeerrRequestStatus.APPROVED,
                media = downloading,
                created = "2026-11-02T09:00:00.000Z",
                updated = "2026-11-03T09:00:00.000Z",
            )
        val ready = request(SeerrRequestStatus.APPROVED, media = arrived, created = "2026-10-30T09:00:00.000Z")
        val declined = request(SeerrRequestStatus.DECLINED, created = "2026-10-01T09:00:00.000Z")

        assertEquals("Requested today", SeerrRequestState.subtitle(pending, now, utc))
        assertEquals("Approved Tue · 62% downloaded", SeerrRequestState.subtitle(approved, now, utc))
        assertEquals("Arrived yesterday", SeerrRequestState.subtitle(ready, now, utc))
        assertEquals("Requested Oct 1", SeerrRequestState.subtitle(declined, now, utc))
    }

    @Test
    fun `the Home row shows waiting requests and last week's arrivals only`() {
        val now = SeerrDates.parse("2026-11-06T18:00:00.000Z")!!
        val recent = info(5).copy(mediaAddedAt = "2026-11-02T10:00:00.000Z")
        val old = info(5).copy(mediaAddedAt = "2026-10-20T10:00:00.000Z")
        assertTrue(SeerrRequestState.showsOnHome(request(SeerrRequestStatus.PENDING), now))
        assertTrue(SeerrRequestState.showsOnHome(request(SeerrRequestStatus.APPROVED, media = info(3)), now))
        assertTrue(SeerrRequestState.showsOnHome(request(SeerrRequestStatus.APPROVED, media = recent), now))
        assertFalse(SeerrRequestState.showsOnHome(request(SeerrRequestStatus.APPROVED, media = old), now))
        assertFalse(SeerrRequestState.showsOnHome(request(SeerrRequestStatus.DECLINED), now))
    }

    @Test
    fun `TMDB show statuses map to the series badge words`() {
        assertEquals("Continuing", SeerrTitleDetails.seriesStatusFor("Returning Series"))
        assertEquals("Continuing", SeerrTitleDetails.seriesStatusFor("In Production"))
        assertEquals("Ended", SeerrTitleDetails.seriesStatusFor("Ended"))
        assertEquals("Ended", SeerrTitleDetails.seriesStatusFor("Canceled"))
        assertEquals("Upcoming", SeerrTitleDetails.seriesStatusFor("Planned"))
        assertNull(SeerrTitleDetails.seriesStatusFor(null))
    }

    @Test
    fun `dates parse with and without fractional seconds and as bare days`() {
        assertEquals(SeerrDates.parse("2026-11-06T09:00:00Z"), SeerrDates.parse("2026-11-06T09:00:00.000Z"))
        assertEquals("2024-11-17T00:00:00Z", SeerrDates.parse("2024-11-17").toString())
        assertNull(SeerrDates.parse("not a date"))
    }
}
