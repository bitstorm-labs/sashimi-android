package dev.bitstorm.sashimi.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ServerScopedClientsTest {
    /** Stand-in client: records which server and token it was built for. */
    private data class FakeClient(
        val url: String,
        val token: String,
    )

    private val shared = FakeClient("https://active.example", "active-token")
    private val a = ServerConfig("a", "Active", "https://active.example", "u", "ua")
    private val b = ServerConfig("b", "Other", "https://other.example", "u", "ub")
    private var active: String? = "a"
    private var servers = listOf(a, b)
    private val tokens = mutableMapOf("a" to "active-token", "b" to "other-token")

    private val clients =
        ServerScopedClients(
            shared = shared,
            activeServerId = { active },
            servers = { servers },
            tokenFor = { tokens[it] },
        ) { server, token -> FakeClient(server.url, token) }

    @Test
    fun `the active server and a null id use the shared client`() {
        assertSame(shared, clients.clientFor(null))
        assertSame(shared, clients.clientFor("a"))
    }

    @Test
    fun `another server gets its own client with its own url and token`() {
        val other = clients.clientFor("b")
        assertEquals(FakeClient("https://other.example", "other-token"), other)
        assertNotSame(shared, other)
        assertSame(other, clients.clientFor("b"))
    }

    @Test
    fun `switching servers moves the shared client, not a pinned one`() {
        val pinnedA = clients.dedicated("a")
        active = "b"
        assertSame(shared, clients.clientFor("b"))
        assertEquals(FakeClient("https://active.example", "active-token"), clients.clientFor("a"))
        assertSame(pinnedA, clients.dedicated("a"))
    }

    @Test
    fun `dedicated never returns the shared client, even for the active server`() {
        val pinned = clients.dedicated("a")
        assertNotSame(shared, pinned)
        assertEquals(FakeClient("https://active.example", "active-token"), pinned)
    }

    @Test
    fun `a removed or signed-out server resolves to nothing, not to the active server`() {
        tokens.remove("b")
        assertNull(clients.clientFor("b"))
        assertNull(clients.forRecord("b"))
        servers = listOf(a)
        assertNull(clients.clientFor("b"))
        assertNull(clients.dedicated("b"))
    }

    @Test
    fun `records resolve by their own server, legacy records by the shared client`() {
        assertSame(shared, clients.forRecord(null))
        assertEquals(FakeClient("https://other.example", "other-token"), clients.forRecord("b"))
        // A record from the active server keeps its own client, so a later
        // switch cannot redirect it.
        assertNotSame(shared, clients.forRecord("a"))
    }
}
