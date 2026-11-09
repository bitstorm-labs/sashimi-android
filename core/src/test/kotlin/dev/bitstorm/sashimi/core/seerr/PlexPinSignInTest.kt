package dev.bitstorm.sashimi.core.seerr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexPinSignInTest {
    private val stub = SeerrStub()
    private val settings = MemorySeerrSettings()
    private var now = 0L

    private fun signIn(timeoutMillis: Long = 15 * 60 * 1000L) =
        PlexPinSignIn(
            settings = settings,
            plexFactory = { PlexPinClient(it, "1.0", stub.http) },
            pollIntervalMillis = 0,
            timeoutMillis = timeoutMillis,
            clock = { now },
        )

    @Test
    fun `begin uses the stored client id`() =
        runTest {
            settings.putString(SeerrSession.KEY_PLEX_CLIENT_ID, "install-uuid")
            stub.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            val pin = signIn().begin(strong = false)
            assertEquals(PlexPin(42, "K7QF"), pin)
            val sent = stub.requests("POST", "/api/v2/pins").single()
            assertEquals("install-uuid", sent.headers["X-Plex-Client-Identifier"])
            assertEquals("false", sent.query("strong"))
        }

    @Test
    fun `web url has no forwardUrl`() {
        settings.putString(SeerrSession.KEY_PLEX_CLIENT_ID, "install-uuid")
        val url = signIn().webSignInUrl(PlexPin(42, "K7QF"))
        assertTrue(url.contains("clientID=install-uuid"))
        assertTrue(url.contains("code=K7QF"))
        assertFalse(url.contains("forwardUrl"))
    }

    @Test
    fun `awaitToken returns the token once linked`() =
        runTest {
            stub.on("GET", "/api/v2/pins/42", status = 500, body = "")
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":null}""")
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            assertEquals("plex-token", signIn().awaitToken(PlexPin(42, "K7QF")) { true })
            assertEquals(3, stub.requests("GET", "/api/v2/pins/42").size)
        }

    @Test
    fun `awaitToken stops when no longer waiting`() =
        runTest {
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF"}""")
            var polls = 0
            assertNull(signIn().awaitToken(PlexPin(42, "K7QF")) { polls++ < 2 })
            assertEquals(2, stub.requests("GET", "/api/v2/pins/42").size)
        }

    @Test
    fun `awaitToken gives null after the timeout`() =
        runTest {
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF"}""")
            val signIn = signIn(timeoutMillis = 1_000)
            val token =
                signIn.awaitToken(PlexPin(42, "K7QF")) {
                    now += 400
                    true
                }
            assertNull(token)
            assertTrue(stub.requests("GET", "/api/v2/pins/42").size in 1..3)
        }
}
