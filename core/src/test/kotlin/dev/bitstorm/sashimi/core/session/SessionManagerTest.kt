package dev.bitstorm.sashimi.core.session

import dev.bitstorm.sashimi.core.model.AuthenticationResult
import dev.bitstorm.sashimi.core.model.PublicSystemInfo
import dev.bitstorm.sashimi.core.model.UserDto
import dev.bitstorm.sashimi.core.network.JellyfinAuthGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [ServerStore]. */
private class FakeServerStore : ServerStore {
    var servers: MutableList<ServerConfig> = mutableListOf()
    var activeId: String? = null

    override fun loadServers(): List<ServerConfig> = servers.toList()

    override fun saveServers(servers: List<ServerConfig>) {
        this.servers = servers.toMutableList()
    }

    override fun getActiveServerId(): String? = activeId

    override fun setActiveServerId(id: String?) {
        activeId = id
    }
}

/** In-memory [TokenStore]. [saveSucceeds] toggles the Keychain-write-failed path. */
private class FakeTokenStore : TokenStore {
    val tokens = mutableMapOf<String, String>()
    var saveSucceeds = true

    override fun get(key: String): String? = tokens[key]

    override fun save(
        value: String,
        key: String,
    ): Boolean {
        if (!saveSucceeds) return false
        tokens[key] = value
        return true
    }

    override fun delete(key: String) {
        tokens.remove(key)
    }
}

/** Fake gateway: records configure() calls and returns a scripted auth result. */
private class FakeGateway : JellyfinAuthGateway {
    override var currentServerUrl: String? = null
    override var sessionExpiredHandler: ((serverUrl: String) -> Unit)? = null

    var authUserId = "user-1"
    var authUserName = "Alice"
    var authToken = "token-abc"
    var clearCredentialsCalls = 0

    override fun configure(
        serverUrl: String,
        accessToken: String?,
        userId: String?,
    ) {
        currentServerUrl = serverUrl
    }

    override fun clearCredentials() {
        clearCredentialsCalls++
        currentServerUrl = null
    }

    override suspend fun authenticate(
        username: String,
        password: String,
    ): AuthenticationResult =
        AuthenticationResult(
            user = UserDto(id = authUserId, name = authUserName),
            accessToken = authToken,
            serverId = "srv-1",
        )

    override suspend fun getPublicSystemInfo(): PublicSystemInfo = PublicSystemInfo(serverName = "Test Server")
}

class SessionManagerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun manager(
        gateway: FakeGateway = FakeGateway(),
        serverStore: FakeServerStore = FakeServerStore(),
        tokenStore: FakeTokenStore = FakeTokenStore(),
    ): Triple<SessionManager, FakeGateway, Pair<FakeServerStore, FakeTokenStore>> {
        var counter = 0
        val sm =
            SessionManager(
                gateway = gateway,
                serverStore = serverStore,
                tokenStore = tokenStore,
                scope = scope,
                idGenerator = { "id-${counter++}" },
            )
        return Triple(sm, gateway, serverStore to tokenStore)
    }

    @Test
    fun `login adds server and authenticates`() =
        runTest {
            val (sm, _, stores) = manager()
            sm.login("https://media.example.com", "alice", "pw")

            assertTrue(sm.isAuthenticated.value)
            assertEquals(1, sm.servers.value.size)
            assertEquals("Test Server", sm.servers.value.first().name)
            assertNotNull(stores.second.get("accessToken.id-0"))
            assertEquals("id-0", sm.activeServerId.value)
        }

    @Test
    fun `login recovers a saved-but-tokenless server instead of erroring duplicate`() =
        runTest {
            // Seed a server that survived a session expiry: entry kept, token gone.
            val serverStore = FakeServerStore()
            val existing =
                ServerConfig(
                    id = "existing-1",
                    name = "Test Server",
                    url = "https://media.example.com",
                    username = "Alice",
                    userId = "user-1",
                )
            serverStore.servers = mutableListOf(existing)
            serverStore.activeId = "existing-1"
            val tokenStore = FakeTokenStore() // no token for existing-1

            val (sm, _, _) = manager(serverStore = serverStore, tokenStore = tokenStore)
            // Startup loads the saved (tokenless) entry into the live state.
            sm.restoreSession()

            // Same URL + same userId as the saved entry → recover, not duplicate.
            sm.login("https://media.example.com", "alice", "pw")

            assertTrue(sm.isAuthenticated.value)
            assertEquals(1, sm.servers.value.size)
            assertEquals("existing-1", sm.activeServerId.value)
            assertEquals("token-abc", tokenStore.get("accessToken.existing-1"))
        }

    @Test
    fun `login throws duplicate when there is a live session for that server`() =
        runTest {
            val (sm, _, _) = manager()
            sm.login("https://media.example.com", "alice", "pw")

            // Second login to the same server with a live session → duplicate.
            val ex = runCatching { sm.login("https://media.example.com", "alice", "pw") }.exceptionOrNull()
            assertTrue(ex is SessionError.DuplicateServer)
            assertTrue(sm.isAuthenticated.value)
            assertEquals(1, sm.servers.value.size)
        }

    @Test
    fun `login fails visibly when token store rejects the write`() =
        runTest {
            val tokenStore = FakeTokenStore().apply { saveSucceeds = false }
            val gateway = FakeGateway()
            val (sm, _, _) = manager(gateway = gateway, tokenStore = tokenStore)

            val ex = runCatching { sm.login("https://media.example.com", "alice", "pw") }.exceptionOrNull()
            assertTrue(ex is SessionError.CredentialStorageFailed)
            assertFalse(sm.isAuthenticated.value)
            assertEquals(0, sm.servers.value.size)
            assertTrue(gateway.clearCredentialsCalls > 0)
        }

    @Test
    fun `switchServer raises reauth signal when token is missing`() =
        runTest {
            val serverStore = FakeServerStore()
            val a =
                ServerConfig("a", "A", "https://a.example.com", "Alice", "user-a")
            val b =
                ServerConfig("b", "B", "https://b.example.com", "Bob", "user-b")
            serverStore.servers = mutableListOf(a, b)
            serverStore.activeId = "a"
            val tokenStore = FakeTokenStore()
            tokenStore.tokens["accessToken.a"] = "tok-a" // b has no token

            val (sm, _, _) = manager(serverStore = serverStore, tokenStore = tokenStore)
            sm.restoreSession()
            assertEquals("a", sm.activeServerId.value)

            sm.switchServer("b")

            // No token for b → reauth prompt, active server unchanged.
            assertEquals("a", sm.activeServerId.value)
            assertNotNull(sm.reauthServer.value)
            assertEquals("b", sm.reauthServer.value?.id)
        }

    @Test
    fun `session expiry on the active server logs out but keeps the entry`() =
        runTest {
            val (sm, gateway, stores) = manager()
            sm.login("https://media.example.com", "alice", "pw")
            val id = sm.activeServerId.value!!
            assertTrue(sm.isAuthenticated.value)

            // The client fires the handler with the active server's URL on a 401.
            gateway.sessionExpiredHandler?.invoke("https://media.example.com")

            assertFalse(sm.isAuthenticated.value)
            // Entry kept so the user can re-authenticate; token dropped.
            assertEquals(1, sm.servers.value.size)
            assertNull(stores.second.get("accessToken.$id"))
            assertEquals(LogoutReason.SESSION_EXPIRED, sm.logoutReason.value)
        }

    @Test
    fun `401 against a non-active server does not expire the live session`() =
        runTest {
            val (sm, gateway, _) = manager()
            sm.login("https://media.example.com", "alice", "pw")
            assertTrue(sm.isAuthenticated.value)

            // A 401 during an Add Server probe targets a DIFFERENT server URL.
            gateway.sessionExpiredHandler?.invoke("https://other.example.com")

            // Live session must survive.
            assertTrue(sm.isAuthenticated.value)
            assertEquals(1, sm.servers.value.size)
        }
}
