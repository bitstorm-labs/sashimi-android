package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import dev.bitstorm.sashimi.core.session.ServerClientRegistry
import dev.bitstorm.sashimi.core.session.ServerConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** Drives the real JellyfinClient request path against a canned OkHttp interceptor. */
class PersonClientTest {
    private val requests = mutableListOf<HttpUrl>()

    private fun http(respond: (HttpUrl) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                synchronized(requests) { requests += url }
                val (code, body) = respond(url)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private fun page(
        ids: List<String>,
        total: Int,
    ) = """{"Items":[${ids.joinToString(",") { """{"Id":"$it","Name":"$it","Type":"Movie"}""" }}],"TotalRecordCount":$total}"""

    @Test
    fun `person media follows every page and filters by person`() =
        runBlocking {
            val client =
                JellyfinClient(
                    "dev",
                    httpClient =
                        http { url ->
                            when (url.queryParameter("StartIndex")) {
                                "0" -> 200 to page(listOf("m1", "m2"), total = 3)
                                "2" -> 200 to page(listOf("s1"), total = 3)
                                else -> 200 to page(emptyList(), total = 3)
                            }
                        },
                )
            client.configure("https://a.example", "tok", "user")
            val media = client.getPersonMedia("person-1", pageSize = 2)

            assertEquals(listOf("m1", "m2", "s1"), media.map { it.id })
            assertEquals(2, requests.size)
            requests.forEach {
                assertEquals("person-1", it.queryParameter("PersonIds"))
                assertEquals("Movie,Series", it.queryParameter("IncludeItemTypes"))
                assertEquals("/Users/user/Items", it.encodedPath)
            }
        }

    @Test
    fun `people search maps the primary image tag`() =
        runBlocking {
            val client =
                JellyfinClient(
                    "dev",
                    httpClient =
                        http {
                            200 to PEOPLE_BODY
                        },
                )
            client.configure("https://a.example", "tok", "user")
            val people = client.searchPeople("Zoe Kravitz", limit = 5)

            assertEquals("p1", people.single().id)
            assertEquals("tag", people.single().primaryImageTag)
            assertEquals("/Persons", requests.single().encodedPath)
            assertEquals("Zoe Kravitz", requests.single().queryParameter("SearchTerm"))
        }

    @Test
    fun `a per-server client leaves the shared one untouched and never signs it out`() =
        runBlocking {
            val shared = JellyfinClient("dev", httpClient = http { 401 to "" })
            shared.configure("https://active.example", "active-token", "active-user")
            var expiredFor: String? = null
            shared.sessionExpiredHandler = { expiredFor = it }

            val other = shared.forServer("https://other.example", "other-token", "other-user")
            try {
                other.getPersonMedia("p", 10)
                fail("expected SessionExpired")
            } catch (e: JellyfinError.SessionExpired) {
                // expected
            }

            assertEquals("https://active.example", shared.currentServerUrl)
            assertEquals("active-token", shared.currentAccessToken)
            assertEquals("https://other.example", other.currentServerUrl)
            assertEquals("other.example", requests.single().host)
            assertNull(expiredFor)
        }

    @Test
    fun `the registry keeps one client per server and rebuilds on a new token`() {
        val a = ServerConfig("a", "A", "https://a.example", "u", "ua")
        val b = ServerConfig("b", "B", "https://b.example", "u", "ub")
        var builds = 0
        val registry = ServerClientRegistry { _, _ -> Any().also { builds++ } }

        val first = registry.clientFor(a, "t1")
        assertSame(first, registry.clientFor(a, "t1"))
        assertNotSame(first, registry.clientFor(b, "t1"))
        assertNotSame(first, registry.clientFor(a, "t2"))
        assertEquals(3, builds)
    }

    private companion object {
        const val PEOPLE_BODY =
            """{"Items":[{"Id":"p1","Name":"Zoë Kravitz","Type":"Person","ImageTags":{"Primary":"tag"}}],"TotalRecordCount":1}"""
    }
}
