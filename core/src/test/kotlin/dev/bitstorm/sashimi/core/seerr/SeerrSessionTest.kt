package dev.bitstorm.sashimi.core.seerr

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of sashimi-apple SeerrSessionTests: signing in to Seerr and staying signed in. */
class SeerrSessionTest {
    private val stub = SeerrStub()
    private val cookies = MemoryTokenStore()
    private val settings = MemorySeerrSettings()
    private val cookieKey = SeerrSession.cookieKey(SeerrFixtures.SERVER)

    private fun session() = SeerrFixtures.session(stub, cookies, settings)

    private fun signInReply(user: String = SeerrFixtures.user()) =
        stub.on("POST", "/api/v1/auth/local", body = user, headers = mapOf("Set-Cookie" to SeerrFixtures.SET_COOKIE))

    @Test
    fun `a new install has no server and is not set up`() {
        val session = SeerrSession(settings, cookies)
        assertEquals(SeerrAuthState.NotConfigured, session.state.value)
        assertFalse(session.isActive)
        assertNull(session.client)
        assertNull("no server is assumed", session.serverUrl.value)
        assertFalse(session.hasServer)
        assertFalse(session.state.value.showsRequestsEntry)
    }

    @Test
    fun `a saved server address survives a relaunch`() {
        settings.putString(SeerrSession.KEY_SERVER, "https://requests.example.com/")
        val session = SeerrSession(settings, cookies)
        assertEquals("https://requests.example.com", session.serverUrl.value)
        assertTrue(session.hasServer)
    }

    @Test
    fun `an address entered on a fresh install is saved for next launch`() {
        assertTrue(SeerrSession(settings, cookies).setServerAddress("requests.example.com"))
        assertEquals("https://requests.example.com", SeerrSession(settings, cookies).serverUrl.value)
    }

    @Test
    fun `an invalid address leaves a fresh install without a server`() {
        val session = SeerrSession(settings, cookies)
        assertFalse(session.setServerAddress("   "))
        assertFalse(session.setServerAddress("ftp://example.com"))
        assertNull(session.serverUrl.value)
        assertNull(settings.getString(SeerrSession.KEY_SERVER))
    }

    private fun sessionWithoutServer() =
        SeerrSession(
            settings = settings,
            cookies = cookies,
            pollIntervalMillis = 0,
            clientFactory = { url, cookie -> SeerrClient(url, cookie, stub.http) },
            plexFactory = { PlexPinClient(it, "1.0", stub.http) },
        )

    @Test
    fun `sign-in is refused without a server and never touches the network`() =
        runTest {
            signInReply()
            stub.on("POST", "/api/v2/pins", body = """{"id":42,"code":"ABCD"}""")
            val session = sessionWithoutServer()
            session.signInLocal("mom@example.com", "pw")
            assertEquals(SeerrAuthState.NotConfigured, session.state.value)
            assertFalse(session.isActive)
            assertNotNull("the person is told why", session.errorMessage.value)
            assertNull("Plex sign-in is gated too", session.beginPlexSignIn())
            assertEquals(SeerrAuthState.NotConfigured, session.state.value)
            session.restore()
            session.refreshPublicSettings()
            assertTrue("no Seerr or Plex calls without an address", stub.requests.isEmpty())
            assertFalse(session.state.value.showsRequestsEntry)
        }

    @Test
    fun `local sign-in stores the cookie under the server and signs in`() =
        runTest {
            signInReply(SeerrFixtures.user(3, "guest"))
            val session = session()
            session.signInLocal("guest@example.com", "pw")
            assertEquals(SeerrAuthState.SignedIn(SeerrUser(id = 3, displayName = "guest", email = "mom@example.com")), session.state.value)
            assertTrue(session.isActive)
            assertNotNull(session.client)
            assertEquals(SeerrFixtures.SESSION_COOKIE, cookies.get(cookieKey))
            assertFalse("the cookie never goes in plain prefs", settings.strings.values.any { it.contains("connect.sid") })
        }

    @Test
    fun `a failed sign-in stays signed out with a message`() =
        runTest {
            stub.on("POST", "/api/v1/auth/local", status = 403, body = """{"message":"Access denied."}""")
            val session = session()
            session.signInLocal("guest@example.com", "nope")
            assertEquals(SeerrAuthState.NotConfigured, session.state.value)
            assertEquals("That email and password didn't work.", session.errorMessage.value)
            assertTrue(cookies.values.isEmpty())
        }

    @Test
    fun `plex sign-in shows the code, then signs in once linked`() =
        runTest {
            stub.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":null}""")
            stub.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            stub.on("POST", "/api/v1/auth/plex", body = SeerrFixtures.user(), headers = mapOf("Set-Cookie" to SeerrFixtures.SET_COOKIE))
            val session = session()

            val pin = session.beginPlexSignIn(strong = true)
            assertEquals(SeerrAuthState.AwaitingPlex("K7QF"), session.state.value)
            assertEquals("true", stub.requests("POST", "/api/v2/pins").single().query("strong"))

            session.completePlexSignIn(pin!!)

            assertEquals(
                7,
                session.state.value.user
                    ?.id,
            )
            assertEquals(2, stub.requests("GET", "/api/v2/pins/42").size)
            assertEquals(
                "plex-token",
                stub
                    .requests("POST", "/api/v1/auth/plex")
                    .single()
                    .json!!["authToken"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(SeerrFixtures.SESSION_COOKIE, cookies.get(cookieKey))
            assertFalse(
                "the Plex token is never stored",
                cookies.values.values.any { it.contains("plex-token") } || settings.strings.values.contains("plex-token"),
            )
        }

    @Test
    fun `the plex client id is stable per install`() =
        runTest {
            stub.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            val session = session()
            session.beginPlexSignIn()
            session.beginPlexSignIn()
            val ids = stub.requests("POST", "/api/v2/pins").map { it.headers["X-Plex-Client-Identifier"] }
            assertEquals(1, ids.toSet().size)
            assertTrue(session.plexWebSignInUrl(PlexPin(42, "K7QF")).contains("clientID=${ids.first()}"))
        }

    @Test
    fun `cancelling plex sign-in goes back to signed out and stops polling`() =
        runTest {
            stub.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            val session = session()
            val pin = session.beginPlexSignIn()!!
            session.cancelPlexSignIn()
            assertEquals(SeerrAuthState.NotConfigured, session.state.value)
            session.completePlexSignIn(pin)
            assertTrue(stub.requests("GET", "/api/v2/pins/42").isEmpty())
        }

    @Test
    fun `restore with a stored cookie checks it with the server`() =
        runTest {
            cookies.save(SeerrFixtures.SESSION_COOKIE, cookieKey)
            stub.on("GET", "/api/v1/auth/me", body = SeerrFixtures.user())
            stub.on("GET", "/api/v1/settings/public", body = """{"applicationTitle":"MondoVision","partialRequestsEnabled":false}""")
            val session = session()
            session.restore()
            assertEquals(
                "mom",
                session.state.value.user
                    ?.name,
            )
            assertEquals("MondoVision", session.applicationTitle.value)
            assertFalse(session.partialRequestsEnabled)
        }

    @Test
    fun `restore without an account never contacts the server`() =
        runTest {
            val session = session()
            session.restore()
            assertEquals(SeerrAuthState.NotConfigured, session.state.value)
            assertTrue("installs that never set up requests stay off the network", stub.requests.isEmpty())
        }

    @Test
    fun `restore with an expired cookie asks to sign in again`() =
        runTest {
            cookies.save(SeerrFixtures.SESSION_COOKIE, cookieKey)
            stub.on("GET", "/api/v1/auth/me", status = 401, body = "{}")
            val session = session()
            session.restore()
            assertEquals(SeerrAuthState.Expired, session.state.value)
            assertFalse(session.isActive)
            assertTrue("the page stays so it can say so", session.state.value.showsRequestsEntry)
            assertNull("a dead cookie is dropped", cookies.get(cookieKey))
        }

    @Test
    fun `restore offline keeps the last known account and its permissions`() =
        runTest {
            signInReply("""{"id":1,"displayName":"owner","permissions":2}""")
            session().signInLocal("a@example.com", "pw")
            stub.reset()
            stub.on("GET", "/api/v1/auth/me", status = 502, body = "Bad gateway")

            val relaunched = session()
            relaunched.restore()

            assertEquals(
                "a server hiccup is not a sign-out",
                1,
                relaunched.state.value.user
                    ?.id,
            )
            assertEquals(
                2,
                relaunched.state.value.user
                    ?.permissions,
            )
            assertTrue(relaunched.permissions.canManageRequests)
        }

    @Test
    fun `an unauthorized answer later is checked and falls back to expired`() =
        runTest {
            signInReply()
            stub.on("GET", "/api/v1/auth/me", status = 403, body = "{}")
            val session = session()
            session.signInLocal("mom@example.com", "pw")
            session.handle(SeerrError.Unauthorized)
            assertEquals(SeerrAuthState.Expired, session.state.value)
        }

    @Test
    fun `an unauthorized answer with a still good session stays signed in`() =
        runTest {
            signInReply()
            stub.on("GET", "/api/v1/auth/me", body = SeerrFixtures.user())
            val session = session()
            session.signInLocal("mom@example.com", "pw")
            session.handle(SeerrError.Unauthorized)
            assertTrue("e.g. a 401 for cancelling a request that is no longer pending", session.isActive)
        }

    @Test
    fun `other errors never sign out`() =
        runTest {
            signInReply()
            val session = session()
            session.signInLocal("mom@example.com", "pw")
            session.handle(SeerrError.Server(500, null))
            session.handle(SeerrError.Network("down"))
            assertTrue(session.isActive)
            assertTrue(stub.requests("GET", "/api/v1/auth/me").isEmpty())
        }

    @Test
    fun `sign out tells the server and forgets the cookie`() =
        runTest {
            signInReply()
            stub.on("POST", "/api/v1/auth/logout", body = """{"status":"ok"}""")
            val session = session()
            session.signInLocal("mom@example.com", "pw")
            session.signOut()
            assertEquals(SeerrAuthState.NotConfigured, session.state.value)
            assertNull(session.client)
            assertNull(cookies.get(cookieKey))
            assertEquals(SeerrFixtures.SESSION_COOKIE, stub.requests("POST", "/api/v1/auth/logout").single().headers["Cookie"])
        }

    @Test
    fun `the server can only change while signed out`() =
        runTest {
            signInReply()
            val session = session()
            session.signInLocal("mom@example.com", "pw")
            assertFalse(session.setServerAddress("other.example.com"))
            assertEquals(SeerrFixtures.SERVER, session.serverUrl.value)
        }

    @Test
    fun `cookies are scoped per server`() {
        assertNotEquals(SeerrSession.cookieKey("https://requests.example.com"), SeerrSession.cookieKey("http://192.168.1.10:5055"))
    }
}
