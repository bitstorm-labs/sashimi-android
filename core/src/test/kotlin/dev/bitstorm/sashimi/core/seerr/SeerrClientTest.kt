package dev.bitstorm.sashimi.core.seerr

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of sashimi-apple SeerrClientTests. Every call goes to the stub. */
class SeerrClientTest {
    private val stub = SeerrStub()

    private fun client(cookie: String? = SeerrFixtures.SESSION_COOKIE) = SeerrClient(SeerrFixtures.SERVER, cookie, stub.http)

    private suspend fun expect(
        error: SeerrError,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("expected $error")
        } catch (e: SeerrError) {
            assertEquals(error, e)
        }
    }

    @Test
    fun `plex sign-in posts the token and keeps only the session cookie`() =
        runTest {
            stub.on("POST", "/api/v1/auth/plex", body = SeerrFixtures.user(), headers = mapOf("Set-Cookie" to SeerrFixtures.SET_COOKIE))
            val session = client(cookie = null).signInWithPlex("plex-token")
            assertEquals(7, session.user.id)
            assertEquals("mom", session.user.name)
            assertEquals("name=value only, no attributes", SeerrFixtures.SESSION_COOKIE, session.cookie)
            val sent = stub.requests.single()
            assertEquals("plex-token", sent.json!!["authToken"]!!.jsonPrimitive.content)
            assertTrue(sent.headers["Content-Type"]!!.startsWith("application/json"))
            assertNull("a sign-in sends no old cookie", sent.headers["Cookie"])
        }

    @Test
    fun `local sign-in posts email and password`() =
        runTest {
            stub.on(
                "POST",
                "/api/v1/auth/local",
                body = SeerrFixtures.user(3, "guest"),
                headers =
                    mapOf(
                        "Set-Cookie" to SeerrFixtures.SET_COOKIE,
                    ),
            )
            assertEquals(3, client(null).signInLocal("guest@example.com", "pw").user.id)
            val body = stub.requests.single().json!!
            assertEquals("guest@example.com", body["email"]!!.jsonPrimitive.content)
            assertEquals("pw", body["password"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a wrong password is invalid credentials, not an expired session`() =
        runTest {
            stub.on("POST", "/api/v1/auth/local", status = 401, body = "{}")
            expect(SeerrError.InvalidCredentials) { client(null).signInLocal("a", "b") }
        }

    @Test
    fun `a sign-in without a session cookie fails`() =
        runTest {
            stub.on("POST", "/api/v1/auth/local", body = SeerrFixtures.user())
            expect(SeerrError.NoSession) { client(null).signInLocal("a", "b") }
        }

    @Test
    fun `every call carries the session cookie`() =
        runTest {
            stub.on("GET", "/api/v1/auth/me", body = SeerrFixtures.user())
            client().currentUser()
            assertEquals(SeerrFixtures.SESSION_COOKIE, stub.requests.single().headers["Cookie"])
        }

    @Test
    fun `a 401 or a 403 is unauthorized`() =
        runTest {
            for (status in listOf(401, 403)) {
                stub.reset()
                stub.on("GET", "/api/v1/auth/me", status = status, body = "{}")
                expect(SeerrError.Unauthorized) { client().currentUser() }
            }
        }

    @Test
    fun `search encodes the query strictly`() {
        assertEquals("dune%3A%20part%20two", SeerrClient.encodeQuery("dune: part two"))
        assertEquals("tom%20%26%20jerry%21", SeerrClient.encodeQuery("tom & jerry!"))
        assertEquals("am%C3%A9lie", SeerrClient.encodeQuery("amélie"))
        assertEquals("a%2Bb%27%28c%29%2A", SeerrClient.encodeQuery("a+b'(c)*"))
    }

    @Test
    fun `search sends the percent-encoded query and drops people`() =
        runTest {
            stub.on(
                "GET",
                "/api/v1/search",
                body =
                    """
                    {"page":1,"totalPages":1,"totalResults":3,"results":[
                      {"id":438631,"mediaType":"movie","title":"Dune","releaseDate":"2021-09-15","posterPath":"/d.jpg","mediaInfo":{"status":5}},
                      {"id":90228,"mediaType":"tv","name":"Dune: Prophecy","firstAirDate":"2024-11-17"},
                      {"id":1,"mediaType":"person","name":"Denis Villeneuve","profilePath":"/p.jpg"}
                    ]}
                    """.trimIndent(),
            )
            val page = client().search("dune: prophecy")
            assertEquals(
                "query=dune%3A%20prophecy&page=1",
                stub.requests
                    .single()
                    .url.encodedQuery,
            )
            assertEquals(listOf("movie-438631", "tv-90228"), page.results.map { it.id })
            assertEquals("Dune: Prophecy", page.results[1].title)
            assertEquals(2024, page.results[1].year)
            assertEquals(SeerrMediaStatus.AVAILABLE, page.results[0].mediaInfo?.mediaStatus)
        }

    @Test
    fun `tv details decode seasons, status, rating and trailer`() =
        runTest {
            stub.on(
                "GET",
                "/api/v1/tv/90228",
                body =
                    """
                    {"id":90228,"name":"Dune: Prophecy","firstAirDate":"2024-11-17","status":"Returning Series",
                     "overview":"Ten thousand years","genres":[{"id":1,"name":"Sci-Fi"},{"id":2,"name":"Drama"}],
                     "contentRatings":{"results":[{"iso_3166_1":"GB","rating":"15"},{"iso_3166_1":"US","rating":"TV-MA"}]},
                     "relatedVideos":[{"key":"clip1","site":"YouTube","type":"Clip"},{"key":"trail1","site":"YouTube","type":"Trailer"}],
                     "seasons":[{"seasonNumber":0,"episodeCount":2},{"seasonNumber":1,"episodeCount":6,"airDate":"2024-11-17"}],
                     "credits":{"cast":[{"id":5,"name":"Emily Watson","character":"Valya"}]},
                     "mediaInfo":{"status":3,"seasons":[{"seasonNumber":1,"status":3}]}}
                    """.trimIndent(),
            )
            val details = client().details(SeerrMediaKind.TV, 90228)
            assertEquals(SeerrMediaKind.TV, details.kind)
            assertEquals("Dune: Prophecy", details.title)
            assertEquals("TV-MA", details.contentRating)
            assertEquals("trail1", details.trailerKey)
            assertEquals("Continuing", details.seriesStatus)
            assertEquals(listOf("Sci-Fi", "Drama"), details.genres)
            assertEquals(listOf(0, 1), details.seasons.map { it.seasonNumber })
            assertEquals("Emily Watson", details.cast.first().name)
            assertEquals(SeerrMediaStatus.PROCESSING, details.mediaInfo?.mediaStatus)
        }

    @Test
    fun `movie details read the US certification`() =
        runTest {
            stub.on(
                "GET",
                "/api/v1/movie/1",
                body =
                    """{"id":1,"title":"Jodorowsky's Dune","releaseDate":"2013-05-01",
                    "releases":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"PG-13"}]}]}}""",
            )
            val details = client().details(SeerrMediaKind.MOVIE, 1)
            assertEquals(SeerrMediaKind.MOVIE, details.kind)
            assertEquals("PG-13", details.contentRating)
            assertNull(details.seriesStatus)
            assertEquals(2013, details.year)
        }

    @Test
    fun `a movie request sends type and tmdb id, no 4K`() =
        runTest {
            stub.on("POST", "/api/v1/request", status = 201, body = """{"id":11,"status":1}""")
            assertEquals(11, client().requestMovie(1).id)
            val body = stub.requests.single().json!!
            assertEquals("movie", body["mediaType"]!!.jsonPrimitive.content)
            assertEquals(1, body["mediaId"]!!.jsonPrimitive.int)
            assertFalse(body.containsKey("seasons"))
            assertFalse(body.containsKey("is4k"))
        }

    @Test
    fun `a show request sends sorted seasons and never specials`() =
        runTest {
            stub.on("POST", "/api/v1/request", status = 201, body = """{"id":12,"status":1}""")
            client().requestShow(90228, listOf(11, 0, 10, 11))
            val body = stub.requests.single().json!!
            assertEquals("tv", body["mediaType"]!!.jsonPrimitive.content)
            assertEquals(90228, body["mediaId"]!!.jsonPrimitive.int)
            assertEquals(listOf(10, 11), body["seasons"]!!.jsonArray.map { it.jsonPrimitive.int })
        }

    @Test
    fun `a show request with no seasons left is refused without calling the server`() =
        runTest {
            expect(SeerrError.NothingToRequest) { client().requestShow(1, listOf(0)) }
            assertTrue(stub.requests.isEmpty())
        }

    @Test
    fun `my requests asks for the user's own, newest first`() =
        runTest {
            stub.on(
                "GET",
                "/api/v1/request",
                body =
                    """{"pageInfo":{"results":1},"results":[{"id":4,"status":2,"type":"movie",
                    "media":{"tmdbId":1,"mediaType":"movie","status":3,"downloadStatus":[{"size":100,"sizeLeft":38}]}}]}""",
            )
            val requests = client().requests(requestedBy = 7)
            val sent = stub.requests.single()
            assertEquals("7", sent.query("requestedBy"))
            assertEquals("50", sent.query("take"))
            assertEquals("added", sent.query("sort"))
            assertEquals(
                38.0,
                requests
                    .first()
                    .media
                    ?.downloadStatus
                    ?.first()
                    ?.sizeLeft,
            )
        }

    @Test
    fun `cancel deletes that request`() =
        runTest {
            stub.on("DELETE", "/api/v1/request/4", status = 204, body = "")
            client().cancelRequest(4)
            assertEquals(1, stub.requests("DELETE", "/api/v1/request/4").size)
        }

    @Test
    fun `a server error carries its message`() =
        runTest {
            stub.on("POST", "/api/v1/request", status = 409, body = """{"message":"Request for this media already exists."}""")
            expect(SeerrError.Server(409, "Request for this media already exists.")) { client().requestMovie(1) }
        }

    @Test
    fun `a server that never answers is a network error, not a sign-out`() =
        runTest {
            val dead = SeerrClient("http://127.0.0.1:1", SeerrFixtures.SESSION_COOKIE)
            try {
                dead.currentUser()
                fail("expected a network error")
            } catch (e: SeerrError.Network) {
                assertEquals("Couldn't reach the request server.", e.message)
            }
        }

    @Test
    fun `the plex pin is created with the app's identity`() =
        runTest {
            stub.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            val pin = PlexPinClient("install-uuid", "1.0", stub.http).createPin(strong = false)
            assertEquals(PlexPin(42, "K7QF"), pin)
            val sent = stub.requests.single()
            assertEquals("plex.tv", sent.url.host)
            assertEquals("strong=false", sent.url.encodedQuery)
            assertEquals("Sashimi", sent.headers["X-Plex-Product"])
            assertEquals("install-uuid", sent.headers["X-Plex-Client-Identifier"])
            assertEquals("application/json", sent.headers["Accept"])
        }

    @Test
    fun `the plex pin poll returns the token once linked`() =
        runTest {
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":null}""")
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            val plex = PlexPinClient("install-uuid", "1.0", stub.http)
            assertNull(plex.checkPin(42).authToken)
            assertEquals("plex-token", plex.checkPin(42).authToken)
        }

    @Test
    fun `the plex web sign-in url carries client, code and product`() {
        val url = PlexPinClient.webSignInUrl("install-uuid", "abc123", "sashimi://plex-auth")
        assertTrue(url.startsWith("https://app.plex.tv/auth/#!?"))
        assertTrue(url.contains("clientID=install-uuid"))
        assertTrue(url.contains("code=abc123"))
        assertTrue(url.contains("context%5Bdevice%5D%5Bproduct%5D=Sashimi"))
        assertTrue(url.contains("forwardUrl=sashimi%3A%2F%2Fplex-auth"))
    }

    @Test
    fun `the session cookie is read from Set-Cookie without attributes`() {
        assertEquals(SeerrFixtures.SESSION_COOKIE, SeerrClient.sessionCookie(listOf("other=1; Path=/", SeerrFixtures.SET_COOKIE)))
        assertNull(SeerrClient.sessionCookie(listOf("connect.sid=; Path=/")))
        assertNull(SeerrClient.sessionCookie(emptyList()))
    }

    /**
     * Found on the emulator: the body was read on the caller's thread (main on
     * Android), which throws NetworkOnMainThreadException for any body not
     * already buffered, so every Discover row failed as "unexpected".
     */
    @Test
    fun `the response body is read off the calling thread`() =
        runTest {
            var readOn: Thread? = null
            val body =
                object : okhttp3.ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()

                    override fun contentLength() = -1L

                    override fun source(): okio.BufferedSource {
                        readOn = Thread.currentThread()
                        return okio.Buffer().writeUtf8(SeerrFixtures.user())
                    }
                }
            val http =
                okhttp3.OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        okhttp3.Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("ok")
                            .body(body)
                            .build()
                    }.build()
            val caller = Thread.currentThread()
            SeerrClient(SeerrFixtures.SERVER, SeerrFixtures.SESSION_COOKIE, http).currentUser()
            assertTrue("read on the caller's thread", readOn != null && readOn != caller)
        }
}
