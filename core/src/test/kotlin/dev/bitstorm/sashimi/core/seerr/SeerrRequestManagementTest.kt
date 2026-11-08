package dev.bitstorm.sashimi.core.seerr

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of sashimi-apple SeerrRequestManagementTests and SeerrViewModelTests:
 * permissions, the request list (filter, scope, paging, actions), Discover,
 * the quota line, the title page, search and the Home row. Every approve,
 * decline, cancel, delete and request goes to the stub, never to a server.
 */
class SeerrRequestManagementTest {
    private val stub = SeerrStub()

    private fun userJson(
        id: Int,
        permissions: Int,
    ) = """{"id":$id,"displayName":"user$id","permissions":$permissions,"avatar":"/avatarproxy/$id"}"""

    private suspend fun session(
        id: Int = 45,
        permissions: Int = 32,
    ) = SeerrFixtures.signedIn(stub, userJson(id, permissions))

    private fun request(
        id: Int,
        status: Int,
        by: Int,
        tmdbId: Int = 100,
        mediaStatus: Int = 2,
    ) = """{"id":$id,"status":$status,"type":"movie","createdAt":"2026-11-01T10:00:00.000Z",
        "media":{"tmdbId":$tmdbId,"mediaType":"movie","status":$mediaStatus},
        "requestedBy":{"id":$by,"displayName":"user$by","avatar":"https://plex.tv/users/$by/avatar"}}"""

    private fun page(
        requests: List<String>,
        total: Int? = null,
    ) = """{"pageInfo":{"pages":1,"pageSize":20,"results":${total ?: requests.size},"page":1},"results":[${requests.joinToString(",")}]}"""

    private val noLibrary: suspend (Int, SeerrMediaKind, String) -> BaseItemDto? = { _, _, _ -> null }

    // MARK: Permissions

    @Test
    fun `permission bits follow Seerr`() {
        val requestOnly = SeerrPermissions(32)
        assertFalse(requestOnly.canViewAllRequests)
        assertFalse(requestOnly.canManageRequests)
        assertTrue(SeerrPermissions(2).canViewAllRequests)
        assertTrue(SeerrPermissions(2).canManageRequests)
        assertTrue(SeerrPermissions(16 or 32).canViewAllRequests)
        assertTrue(SeerrPermissions(16 or 32).canManageRequests)
        val viewer = SeerrPermissions(16_384 or 32)
        assertTrue(viewer.canViewAllRequests)
        assertFalse("REQUEST_VIEW sees everyone's but can't approve", viewer.canManageRequests)
    }

    @Test
    fun `requester name and avatar resolve against the server`() {
        val user = SeerrJson.json.decodeFromString<SeerrRequestUser>(userJson(9, 32))
        assertEquals("user9", user.name)
        assertEquals("https://seerr.test/avatarproxy/9", user.avatarUrl(SeerrFixtures.SERVER))
        val plex = SeerrRequestUser(id = 3, avatar = "https://plex.tv/users/3/avatar")
        assertEquals("https://plex.tv/users/3/avatar", plex.avatarUrl(SeerrFixtures.SERVER))
        assertEquals("User 3", plex.name)
    }

    // MARK: Request list: a REQUEST-only account (sashimi-test)

    @Test
    fun `a request-only account only ever asks for its own requests`() =
        runTest {
            val session = session(45, 32)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(1, 1, 45))))
            val model = SeerrRequestList(session)
            model.scope = SeerrRequestScope.EVERYONE
            model.reload()
            assertFalse(model.canChooseScope)
            assertEquals(SeerrRequestScope.MINE, model.effectiveScope)
            assertFalse(model.showsRequester)
            val sent = stub.requests("GET", "/api/v1/request").first()
            assertEquals("45", sent.query("requestedBy"))
            assertEquals("all", sent.query("filter"))
            assertTrue("the count is everyone's", stub.requests("GET", "/api/v1/request/count").isEmpty())
            assertNull(model.state.value.count)
        }

    @Test
    fun `a requester can cancel only their own pending request`() =
        runTest {
            val session = session(45, 32)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(1, 1, 45), request(2, 2, 45, tmdbId = 101))))
            val model = SeerrRequestList(session)
            model.reload()
            val pending = model.rows.first { it.id == 1 }
            val approved = model.rows.first { it.id == 2 }
            assertEquals(listOf(SeerrRequestAction.CANCEL), model.actions(pending))
            assertEquals(emptyList<SeerrRequestAction>(), model.actions(approved))

            model.perform(SeerrRequestAction.APPROVE, pending)
            model.perform(SeerrRequestAction.DELETE, approved)
            assertTrue("nothing the account lacks is sent", stub.requests.none { it.method != "GET" })

            stub.on("DELETE", "/api/v1/request/1", status = 204, body = "")
            model.perform(SeerrRequestAction.CANCEL, pending)
            assertEquals(1, stub.requests("DELETE", "/api/v1/request/1").size)
            assertFalse(model.rows.any { it.id == 1 })
            assertEquals(1, session.requestsRevision.value)
        }

    // MARK: Request list: an administrator

    @Test
    fun `an administrator sees everyone and the count`() =
        runTest {
            val session = session(1, 2)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(5, 1, 7))))
            stub.on("GET", "/api/v1/request/count", body = """{"total":493,"pending":0,"processing":78,"available":56}""")
            val model = SeerrRequestList(session)
            model.filter = SeerrRequestFilter.PENDING
            model.reload()
            assertTrue(model.canChooseScope)
            assertTrue(model.showsRequester)
            val sent = stub.requests("GET", "/api/v1/request").first()
            assertNull(sent.query("requestedBy"))
            assertEquals("pending", sent.query("filter"))
            assertEquals("added", sent.query("sort"))
            assertEquals("desc", sent.query("sortDirection"))
            assertEquals(
                "493 requests · 0 pending · 78 processing · 56 available",
                model.state.value.count
                    ?.summary,
            )
            val row = model.rows.first()
            assertEquals("user7", row.requesterName)
            assertEquals(listOf(SeerrRequestAction.APPROVE, SeerrRequestAction.DECLINE, SeerrRequestAction.DELETE), model.actions(row))
        }

    @Test
    fun `the list opens on everyone's requests for those who may see them`() =
        runTest {
            val session = session(1, 2)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(5, 2, 7))))
            val model = SeerrRequestList(session)
            model.reload()
            assertEquals("#855: all requests by default", SeerrRequestScope.EVERYONE, model.effectiveScope)
            assertEquals("Everyone comes first in the switch", SeerrRequestScope.EVERYONE, SeerrRequestScope.entries.first())
            assertNull(stub.requests("GET", "/api/v1/request").first().query("requestedBy"))
        }

    @Test
    fun `a request viewer sees everyone's but gets no actions on others' requests`() =
        runTest {
            val session = session(1, 16_384 or 32)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(5, 1, 7))))
            val model = SeerrRequestList(session)
            model.reload()
            assertTrue(model.canChooseScope)
            assertEquals(emptyList<SeerrRequestAction>(), model.actions(model.rows.first()))
            model.scope = SeerrRequestScope.MINE
            model.reload()
            assertEquals("1", stub.requests("GET", "/api/v1/request").last().query("requestedBy"))
        }

    @Test
    fun `approving posts and the pending filter drops the row`() =
        runTest {
            val session = session(1, 16 or 32)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(5, 1, 7))))
            stub.on("POST", "/api/v1/request/5/approve", body = """{"id":5,"status":2}""")
            val model = SeerrRequestList(session)
            model.filter = SeerrRequestFilter.PENDING
            model.reload()
            model.perform(SeerrRequestAction.APPROVE, model.rows.first())
            assertEquals(1, stub.requests("POST", "/api/v1/request/5/approve").size)
            assertTrue(model.rows.isEmpty())
            assertEquals(1, session.requestsRevision.value)
        }

    @Test
    fun `declining under All keeps the row as declined`() =
        runTest {
            val session = session(1, 2)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(5, 1, 7))))
            stub.on("POST", "/api/v1/request/5/decline", body = """{"id":5,"status":3}""")
            val model = SeerrRequestList(session)
            model.reload()
            model.perform(SeerrRequestAction.DECLINE, model.rows.first())
            assertEquals(1, stub.requests("POST", "/api/v1/request/5/decline").size)
            assertEquals(
                "Declined",
                model.rows
                    .first()
                    .requestStatus.label,
            )
            assertEquals(listOf(SeerrRequestAction.DELETE), model.actions(model.rows.first()))
        }

    @Test
    fun `a failed action says why and leaves the row`() =
        runTest {
            val session = session(1, 2)
            stub.on("GET", "/api/v1/request", body = page(listOf(request(5, 1, 7))))
            stub.on(
                "POST",
                "/api/v1/request/5/approve",
                status = 409,
                body = """{"message":"Only pending requests can be approved or declined."}""",
            )
            val model = SeerrRequestList(session)
            model.reload()
            model.perform(SeerrRequestAction.APPROVE, model.rows.first())
            assertEquals("Only pending requests can be approved or declined.", model.state.value.errorMessage)
            assertEquals(
                "Pending",
                model.rows
                    .first()
                    .requestStatus.label,
            )
            assertTrue(
                model.state.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `the next page loads near the end`() =
        runTest {
            val session = session(45, 32)
            stub.on("GET", "/api/v1/request", body = page((1..3).map { request(it, 2, 45, tmdbId = 100 + it) }, total = 4))
            stub.on("GET", "/api/v1/request", body = page(listOf(request(4, 2, 45, tmdbId = 104)), total = 4))
            val model = SeerrRequestList(session, pageSize = 3)
            model.reload()
            assertTrue(model.state.value.canLoadMore)
            model.loadMoreIfNeeded(model.rows.last())
            assertEquals(listOf(1, 2, 3, 4), model.rows.map { it.id })
            assertFalse(model.state.value.canLoadMore)
            assertEquals(listOf("0", "3"), stub.requests("GET", "/api/v1/request").map { it.query("skip") })
        }

    @Test
    fun `rows describe the request`() {
        val request =
            SeerrJson.json.decodeFromString<SeerrRequest>(
                """{"id":8,"status":2,"type":"tv","createdAt":"2026-11-02T10:00:00.000Z","seasons":[{"seasonNumber":1},{"seasonNumber":2},{"seasonNumber":3}],
                "media":{"tmdbId":5,"mediaType":"tv","status":3,"downloadStatus":[{"size":100,"sizeLeft":25}]},"requestedBy":{"id":3,"displayName":"chad"}}""",
            )
        val row = SeerrRequestRow.of(request, SeerrTitleCache())!!
        assertEquals("Series · S1–3", row.kindLabel)
        assertEquals("Unknown title · S1–3", row.displayTitle)
        assertEquals("chad", row.requesterName)
        assertEquals(SeerrStatusLabel("Approved", SeerrBadge.COMING), row.requestStatus)
        assertEquals(SeerrStatusLabel("Processing", SeerrBadge.COMING), row.mediaStatus)
        assertEquals(0.75, row.progress!!, 0.001)
    }

    // MARK: Discover

    @Test
    fun `discover lists Seerr's rows in order and drops an empty watchlist`() =
        runTest {
            val session = session(45, 32)

            fun results(
                id: Int,
                kind: String,
                status: Int? = null,
            ): String {
                val info = status?.let { ""","mediaInfo":{"status":$it}""" } ?: ""
                val name = if (kind == "movie") """"title":"M$id"""" else """"name":"S$id""""
                return """{"page":1,"totalPages":3,"results":[{"id":$id,"mediaType":"$kind",$name$info}]}"""
            }
            stub.on("GET", "/api/v1/request", body = page(listOf(request(1, 1, 45, tmdbId = 9))))
            stub.on("GET", "/api/v1/movie/9", body = """{"id":9,"title":"Requested","posterPath":"/r.jpg"}""")
            stub.on("GET", "/api/v1/discover/trending", body = results(10, "movie", 5))
            stub.on("GET", "/api/v1/discover/movies", body = results(11, "movie"))
            stub.on("GET", "/api/v1/discover/movies/upcoming", body = results(12, "movie"))
            stub.on("GET", "/api/v1/discover/tv", body = results(13, "tv", 3))
            stub.on("GET", "/api/v1/discover/tv/upcoming", body = results(14, "tv"))
            stub.on("GET", "/api/v1/discover/watchlist", body = """{"page":1,"totalPages":1,"totalResults":0,"results":[]}""")
            val model = SeerrDiscover(session)
            model.load()
            val rows = model.rows
            assertEquals(
                listOf(
                    SeerrDiscoverSlider.RECENT_REQUESTS,
                    SeerrDiscoverSlider.TRENDING,
                    SeerrDiscoverSlider.POPULAR_MOVIES,
                    SeerrDiscoverSlider.UPCOMING_MOVIES,
                    SeerrDiscoverSlider.POPULAR_SERIES,
                    SeerrDiscoverSlider.UPCOMING_SERIES,
                ),
                rows.map { it.slider },
            )
            val recent = rows.first().items.first()
            assertEquals("Requested", recent.result.title)
            assertEquals("/r.jpg", recent.result.posterPath)
            assertEquals(SeerrBadge.PENDING, recent.badge)
            assertEquals(SeerrBadge.AVAILABLE, rows[1].items.first().badge)
            assertEquals(SeerrBadge.COMING, rows[4].items.first().badge)
            assertEquals("Requested", recent.discoverTag?.label)
            assertEquals(
                "Available",
                rows[1]
                    .items
                    .first()
                    .discoverTag
                    ?.label,
            )
            assertEquals(
                "Processing",
                rows[4]
                    .items
                    .first()
                    .discoverTag
                    ?.label,
            )
            assertNull("nobody has asked for it: no pill", rows[2].items.first().discoverTag)
            val sent = stub.requests("GET", "/api/v1/request").first()
            assertNull("the server scopes Recent Requests itself", sent.query("requestedBy"))
            assertEquals("10", sent.query("take"))
        }

    @Test
    fun `a plex watchlist gets its posters from the details`() =
        runTest {
            val session = session(45, 32)
            stub.on(
                "GET",
                "/api/v1/discover/watchlist",
                body =
                    """{"page":1,"totalPages":1,"totalResults":1,
                    "results":[{"id":603,"tmdbId":603,"ratingKey":"abc","title":"The Matrix","mediaType":"movie"}]}""",
            )
            stub.on("GET", "/api/v1/movie/603", body = """{"id":603,"title":"The Matrix","posterPath":"/m.jpg","mediaInfo":{"status":5}}""")
            val model = SeerrDiscover(session)
            model.load()
            val item =
                model.rows
                    .first { it.slider == SeerrDiscoverSlider.WATCHLIST }
                    .items
                    .first()
            assertEquals(603, item.result.tmdbId)
            assertEquals("/m.jpg", item.result.posterPath)
            assertEquals(SeerrBadge.AVAILABLE, item.badge)
        }

    @Test
    fun `a local watchlist reads the tmdb id, not the entry id`() {
        val page =
            SeerrSearchPage.parse(
                """{"page":1,"totalPages":1,"results":[{"id":4,"tmdbId":603,"title":"The Matrix","mediaType":"movie"}]}""",
            )
        assertEquals(603, page.results.first().tmdbId)
    }

    @Test
    fun `a row pages on near its end`() =
        runTest {
            val session = session(45, 32)
            stub.on(
                "GET",
                "/api/v1/discover/trending",
                body = """{"page":1,"totalPages":2,"results":[{"id":1,"mediaType":"movie","title":"A"}]}""",
            )
            stub.on(
                "GET",
                "/api/v1/discover/trending",
                body = """{"page":2,"totalPages":2,"results":[{"id":2,"mediaType":"movie","title":"B"}]}""",
            )
            val model = SeerrDiscover(session)
            model.load()
            val row = model.rows.first { it.slider == SeerrDiscoverSlider.TRENDING }
            model.loadMoreIfNeeded(SeerrDiscoverSlider.TRENDING, row.items.last())
            assertEquals(
                listOf("A", "B"),
                model.rows
                    .first { it.slider == SeerrDiscoverSlider.TRENDING }
                    .items
                    .map { it.result.title },
            )
            assertEquals(listOf("1", "2"), stub.requests("GET", "/api/v1/discover/trending").map { it.query("page") })
        }

    // MARK: Search

    @Test
    fun `without an account search never asks Seerr`() =
        runTest {
            val model = SeerrSearch(SeerrFixtures.session(stub))
            model.search("dune", emptyList())
            assertTrue(model.items.value.isEmpty())
            assertFalse(model.isVisible)
            assertTrue(stub.requests.isEmpty())
        }

    @Test
    fun `the search row carries pills and skips the library`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on(
                "GET",
                "/api/v1/search",
                body =
                    """{"page":1,"totalPages":1,"results":[
                    {"id":90228,"mediaType":"tv","name":"Dune: Prophecy"},
                    {"id":841,"mediaType":"movie","title":"Dune","releaseDate":"1984-12-14","mediaInfo":{"status":2}},
                    {"id":438631,"mediaType":"movie","title":"Dune","mediaInfo":{"status":5}}]}""",
            )
            val model = SeerrSearch(session)
            model.search("dune", emptyList())
            assertEquals(listOf(90228, 841), model.items.value.map { it.result.tmdbId })
            assertEquals(listOf(SeerrBadge.REQUEST, SeerrBadge.PENDING), model.items.value.map { it.badge })
            assertTrue(model.isVisible)
        }

    @Test
    fun `the page's search keeps titles that are on the server`() =
        runTest {
            val session = session(45, 32)
            stub.on(
                "GET",
                "/api/v1/search",
                body =
                    """{"page":1,"totalPages":1,"results":[{"id":1,"mediaType":"movie","title":"On server","mediaInfo":{"status":5}},
                    {"id":2,"mediaType":"movie","title":"Not yet"}]}""",
            )
            val model = SeerrSearch(session, includesAvailable = true)
            model.search("x", emptyList())
            assertEquals(listOf(SeerrBadge.AVAILABLE, SeerrBadge.REQUEST), model.items.value.map { it.badge })
        }

    @Test
    fun `an expired session during search signs out`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on("GET", "/api/v1/search", status = 401, body = "{}")
            stub.on("GET", "/api/v1/auth/me", status = 401, body = "{}")
            val model = SeerrSearch(session)
            model.search("dune", emptyList())
            assertTrue(model.items.value.isEmpty())
            assertEquals(SeerrAuthState.Expired, session.state.value)
        }

    // MARK: Quota and the title page

    @Test
    fun `quota lines`() {
        val unlimited =
            SeerrJson.json.decodeFromString<SeerrQuota>(
                """{"movie":{"days":7,"limit":0,"used":0,"restricted":false},"tv":{"days":7,"limit":0,"used":0,"restricted":false}}""",
            )
        assertNull(unlimited.summary(SeerrMediaKind.MOVIE))
        assertNull(unlimited.summary(SeerrMediaKind.TV))
        val limited =
            SeerrQuota(
                movie = SeerrQuotaStatus(days = 7, limit = 5, used = 2, remaining = 3, restricted = false),
                tv = SeerrQuotaStatus(days = 1, limit = 4, used = 4, remaining = 0, restricted = true),
            )
        assertEquals("3 of 5 movie requests left · resets over 7 days", limited.summary(SeerrMediaKind.MOVIE))
        assertEquals("You've used all 4 of your season requests · resets over 1 day", limited.summary(SeerrMediaKind.TV))
    }

    @Test
    fun `the request page shows the quota under the button`() =
        runTest {
            val session = session(45, 32)
            stub.on("GET", "/api/v1/movie/1", body = """{"id":1,"title":"Jodorowsky's Dune"}""")
            stub.on("GET", "/api/v1/user/45/quota", body = """{"movie":{"days":7,"limit":5,"used":1,"remaining":4,"restricted":false}}""")
            val model = SeerrTitle(SeerrTarget(SeerrMediaKind.MOVIE, 1, "Jodorowsky's Dune"), session, noLibrary)
            model.load()
            assertEquals(SeerrTitle.Action.Request, model.action)
            assertEquals("4 of 5 movie requests left · resets over 7 days", model.state.value.quotaNote)
        }

    @Test
    fun `a movie requests in one press and the button becomes a status line`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on("GET", "/api/v1/movie/1", body = """{"id":1,"title":"Jodorowsky's Dune","releaseDate":"2013-05-01"}""")
            stub.on(
                "GET",
                "/api/v1/movie/1",
                body =
                    """{"id":1,"title":"Jodorowsky's Dune",
                    "mediaInfo":{"status":2,"requests":[{"id":11,"status":1,"requestedBy":{"id":7}}]}}""",
            )
            stub.on("POST", "/api/v1/request", status = 201, body = """{"id":11,"status":1}""")
            val model = SeerrTitle(SeerrTarget(SeerrMediaKind.MOVIE, 1, "Jodorowsky's Dune"), session, noLibrary)
            model.load()
            assertEquals(SeerrTitle.Action.Request, model.action)
            model.requestMovie()
            assertEquals(1, stub.requests("POST", "/api/v1/request").size)
            assertEquals(SeerrTitle.Action.Status("Requested · waiting for approval"), model.action)
            assertEquals(1, session.requestsRevision.value)
        }

    @Test
    fun `a show opens the picker and sends the ticked seasons`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on(
                "GET",
                "/api/v1/tv/90228",
                body =
                    """{"id":90228,"name":"Dune: Prophecy","seasons":[{"seasonNumber":1,"episodeCount":6,"airDate":"2024-11-17"},
                    {"seasonNumber":2,"episodeCount":0,"airDate":null}]}""",
            )
            stub.on("POST", "/api/v1/request", status = 201, body = """{"id":12,"status":1}""")
            val model = SeerrTitle(SeerrTarget(SeerrMediaKind.TV, 90228, "Dune: Prophecy"), session, noLibrary)
            model.load()
            assertEquals(SeerrTitle.Action.ChooseSeasons, model.action)
            val plan = model.seasonPlan()!!
            assertEquals("Request 1 season", plan.buttonTitle)
            model.requestSeasons(plan)
            val body = stub.requests("POST", "/api/v1/request").single().json!!
            assertEquals(listOf(1), body["seasons"]!!.jsonArray.map { it.jsonPrimitive.int })
        }

    @Test
    fun `coming and available states, and an available title opens the library one`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on("GET", "/api/v1/movie/1", body = """{"id":1,"title":"A","mediaInfo":{"status":3}}""")
            val coming = SeerrTitle(SeerrTarget(SeerrMediaKind.MOVIE, 1, "A"), session, noLibrary)
            coming.load()
            assertEquals(SeerrTitle.Action.Status("Coming soon"), coming.action)

            stub.on("GET", "/api/v1/movie/2", body = """{"id":2,"title":"B","mediaInfo":{"status":5}}""")
            var asked: Pair<Int, SeerrMediaKind>? = null
            val available =
                SeerrTitle(SeerrTarget(SeerrMediaKind.MOVIE, 2, "B"), session) { id, kind, _ ->
                    asked = id to kind
                    BaseItemDto(id = "jf-2", name = "B", type = ItemType.MOVIE)
                }
            available.load()
            assertEquals(
                "the page offers the library title",
                "jf-2",
                available.state.value.libraryItem
                    ?.id,
            )
            assertEquals(2 to SeerrMediaKind.MOVIE, asked)
            assertEquals(SeerrTitle.Action.Status("On your server"), available.action)
        }

    @Test
    fun `a failed request keeps the button and says why`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on("GET", "/api/v1/movie/1", body = """{"id":1,"title":"Jodorowsky's Dune"}""")
            stub.on("POST", "/api/v1/request", status = 403, body = """{"message":"You do not have permission to request."}""")
            stub.on("GET", "/api/v1/auth/me", body = SeerrFixtures.user())
            val model = SeerrTitle(SeerrTarget(SeerrMediaKind.MOVIE, 1, "A"), session, noLibrary)
            model.load()
            model.requestMovie()
            assertEquals(SeerrTitle.Action.Request, model.action)
            assertNotNull(model.state.value.errorMessage)
            assertTrue(session.isActive)
            assertFalse(model.state.value.isSubmitting)
        }

    @Test
    fun `signed out, the title page says where to sign in and asks nothing`() =
        runTest {
            val model = SeerrTitle(SeerrTarget(SeerrMediaKind.MOVIE, 1, "A"), SeerrFixtures.session(stub), noLibrary)
            model.load()
            assertTrue(model.action is SeerrTitle.Action.Failed)
            assertTrue(stub.requests.isEmpty())
        }

    // MARK: Request missing seasons (a library show's menu)

    private fun libraryShow(tmdb: String?) =
        BaseItemDto(
            id = "s1",
            name = "Love Is Blind",
            type = ItemType.SERIES,
            providerIds =
                tmdb?.let {
                    mapOf(
                        "Tmdb" to it,
                    )
                },
        )

    @Test
    fun `a library show missing seasons offers to request them`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on(
                "GET",
                "/api/v1/tv/77",
                body =
                    """{"id":77,"name":"Love Is Blind","seasons":[{"seasonNumber":1,"episodeCount":10,"airDate":"2020-02-13"},
                    {"seasonNumber":2,"episodeCount":10,"airDate":"2022-02-13"}],"mediaInfo":{"status":4,"seasons":[{"seasonNumber":1,"status":5}]}}""",
            )
            val target = SeerrTitle.missingSeasonsTarget(libraryShow("77"), session)
            assertEquals(77, target?.tmdbId)
            assertEquals(SeerrMediaKind.TV, target?.kind)
        }

    @Test
    fun `a show with every season on the server offers nothing`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on(
                "GET",
                "/api/v1/tv/77",
                body =
                    """{"id":77,"name":"Love Is Blind","seasons":[{"seasonNumber":1,"episodeCount":10,"airDate":"2020-02-13"}],
                    "mediaInfo":{"status":5,"seasons":[{"seasonNumber":1,"status":5}]}}""",
            )
            assertNull(SeerrTitle.missingSeasonsTarget(libraryShow("77"), session))
        }

    @Test
    fun `missing seasons needs an account and a tmdb id`() =
        runTest {
            assertNull(SeerrTitle.missingSeasonsTarget(libraryShow("77"), SeerrFixtures.session(stub)))
            val session = SeerrFixtures.signedIn(stub)
            assertNull(SeerrTitle.missingSeasonsTarget(libraryShow(null), session))
            assertTrue(stub.requests.isEmpty())
        }

    // MARK: Home requests row

    private val requestsJson =
        """{"pageInfo":{"page":1,"pages":1,"results":2},"results":[
        {"id":4,"status":1,"type":"movie","createdAt":"2026-11-06T10:00:00.000Z","media":{"tmdbId":1,"mediaType":"movie","status":2},"requestedBy":{"id":7}},
        {"id":5,"status":2,"type":"movie","createdAt":"2026-11-01T10:00:00.000Z","media":{"tmdbId":2,"mediaType":"movie","status":3},"requestedBy":{"id":7}}]}"""

    @Test
    fun `your requests loads the user's requests with titles`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub)
            stub.on("GET", "/api/v1/request", body = requestsJson)
            stub.on("GET", "/api/v1/movie/1", body = """{"id":1,"title":"First","posterPath":"/1.jpg"}""")
            stub.on("GET", "/api/v1/movie/2", body = """{"id":2,"title":"Second"}""")
            val model = SeerrHomeRequests(session)
            model.load()
            assertEquals(listOf("First", "Second"), model.rows.value.map { it.title })
            assertEquals(listOf(SeerrRequestState.Pending, SeerrRequestState.Coming(null)), model.rows.value.map { it.state })
            assertEquals("7", stub.requests("GET", "/api/v1/request").first().query("requestedBy"))
            assertEquals(2, model.homeRows().size)
            assertEquals("Your Requests", model.title)
            assertNull("always the person themselves, so no name", model.requester(model.rows.value.first()))
        }

    @Test
    fun `the Home requests row is everyone's for those who may see them, one poster per title`() =
        runTest {
            val session = SeerrFixtures.signedIn(stub, """{"id":1,"displayName":"owner","permissions":2}""")
            stub.on(
                "GET",
                "/api/v1/request",
                body =
                    """{"pageInfo":{"page":1,"pages":1,"results":3},"results":[
                    {"id":9,"status":2,"type":"movie","createdAt":"2026-11-06T10:00:00.000Z","media":{"tmdbId":1,"mediaType":"movie","status":3},"requestedBy":{"id":41,"displayName":"Kurt"}},
                    {"id":8,"status":2,"type":"movie","createdAt":"2026-11-05T10:00:00.000Z","media":{"tmdbId":1,"mediaType":"movie","status":3},"requestedBy":{"id":18}},
                    {"id":7,"status":1,"type":"movie","createdAt":"2026-11-04T10:00:00.000Z","media":{"tmdbId":2,"mediaType":"movie","status":2},"requestedBy":{"id":6}}]}""",
            )
            stub.on("GET", "/api/v1/movie/1", body = """{"id":1,"title":"First"}""")
            stub.on("GET", "/api/v1/movie/2", body = """{"id":2,"title":"Second"}""")
            val model = SeerrHomeRequests(session)
            model.load()
            assertNull("everyone's, not just mine", stub.requests("GET", "/api/v1/request").first().query("requestedBy"))
            assertEquals("the newest request per title", listOf(9, 7), model.rows.value.map { it.id })
            assertEquals("Recent Requests", model.title)
            assertEquals("who asked, under the title", "Kurt", model.requester(model.rows.value.first()))
        }

    @Test
    fun `signed out has no requests and asks nothing`() =
        runTest {
            val model = SeerrHomeRequests(SeerrFixtures.session(stub))
            model.load()
            assertTrue(model.rows.value.isEmpty())
            assertTrue(stub.requests.isEmpty())
        }
}
