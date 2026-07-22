package dev.bitstorm.sashimi.core.session

import dev.bitstorm.sashimi.core.model.UserDto
import dev.bitstorm.sashimi.core.network.JellyfinAuthGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Multi-server session manager — the Android port of
 * Shared/Services/SessionManager.swift. Preserves the hard-won session lessons
 * from the Swift original (each is called out at its method). Swift used
 * @Published on a @MainActor object; here the same state is exposed as
 * [StateFlow]s and the persistence is injected so the recover/reauth logic is
 * unit-testable without Android.
 */
class SessionManager(
    private val gateway: JellyfinAuthGateway,
    private val serverStore: ServerStore,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
    /** Seam for deterministic tests; production uses random UUIDs. */
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _activeServerId = MutableStateFlow<String?>(null)
    val activeServerId: StateFlow<String?> = _activeServerId.asStateFlow()

    private val _logoutReason = MutableStateFlow<LogoutReason?>(null)
    val logoutReason: StateFlow<LogoutReason?> = _logoutReason.asStateFlow()

    /**
     * Set when the user picks a saved server whose token was dropped by a past
     * session expiry — the UI presents a prefilled re-auth for it. Cleared on
     * success or cancel.
     */
    private val _reauthServer = MutableStateFlow<ServerConfig?>(null)
    val reauthServer: StateFlow<ServerConfig?> = _reauthServer.asStateFlow()

    val activeServer: ServerConfig?
        get() = _servers.value.firstOrNull { it.id == _activeServerId.value }

    init {
        // Wire the 401 handler: the client fires this with the failing request's
        // server URL; we only expire the session when it targeted the ACTIVE
        // server (401-only-on-active-server lesson, JellyfinClient.swift).
        gateway.sessionExpiredHandler = { url -> onSessionExpired(url) }
    }

    fun clearReauthServer() {
        _reauthServer.value = null
    }

    fun consumeLogoutReason() {
        _logoutReason.value = null
    }

    private fun tokenKey(id: String): String = "accessToken.$id"

    // MARK: - Session lifecycle

    suspend fun restoreSession() {
        _servers.value = serverStore.loadServers()
        _activeServerId.value = serverStore.getActiveServerId()

        val server = activeServer ?: _servers.value.firstOrNull() ?: return
        val token = tokenStore.get(tokenKey(server.id)) ?: return
        if (_activeServerId.value != server.id) {
            _activeServerId.value = server.id
            saveServers()
        }
        activate(server, token)
    }

    private fun activate(
        server: ServerConfig,
        token: String,
    ) {
        gateway.configure(serverUrl = server.url, accessToken = token, userId = server.userId)
        _serverUrl.value = server.url
        _currentUser.value = UserDto(id = server.userId, name = server.username)
        _isAuthenticated.value = true
    }

    // MARK: - Add / switch / remove

    /**
     * Signs into a server and ADDS it to the saved list (making it active).
     *
     * Recovery lesson (SessionManager.swift): if the server is already saved but
     * has NO live session, its entry survived a past session-expiry/sign-out
     * (the entry is kept, the token dropped). Rather than erroring "duplicate",
     * re-save the fresh token and reactivate — the user is re-authenticating. A
     * genuine duplicate (live session for that exact server) still throws
     * DuplicateServer.
     */
    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        gateway.configure(serverUrl = serverUrl)

        val result = gateway.authenticate(username, password)

        val existing = _servers.value.firstOrNull { it.url == serverUrl && it.userId == result.user.id }
        if (existing != null) {
            val hasLiveSession =
                _isAuthenticated.value &&
                    _activeServerId.value == existing.id &&
                    tokenStore.get(tokenKey(existing.id)) != null
            if (hasLiveSession) {
                // Genuine duplicate: restore the active client (authenticate()
                // repointed it) and report it.
                activeServer?.let { current ->
                    tokenStore.get(tokenKey(current.id))?.let { token -> activate(current, token) }
                }
                throw SessionError.DuplicateServer
            }
            if (!tokenStore.save(result.accessToken, tokenKey(existing.id))) {
                gateway.clearCredentials()
                throw SessionError.CredentialStorageFailed
            }
            _activeServerId.value = existing.id
            saveServers()
            activate(existing, result.accessToken)
            return
        }

        var serverName = hostOf(serverUrl) ?: "Jellyfin"
        runCatching { gateway.getPublicSystemInfo() }.getOrNull()?.serverName?.let { serverName = it }

        val config =
            ServerConfig(
                id = idGenerator(),
                name = serverName,
                url = serverUrl,
                username = result.user.name,
                userId = result.user.id,
            )

        // Persist the token first: if the store rejects it, fail the login
        // visibly rather than leaving a session that vanishes on next launch.
        if (!tokenStore.save(result.accessToken, tokenKey(config.id))) {
            gateway.clearCredentials()
            throw SessionError.CredentialStorageFailed
        }

        _servers.value = _servers.value + config
        _activeServerId.value = config.id
        saveServers()

        _serverUrl.value = serverUrl
        _currentUser.value = result.user
        _logoutReason.value = null
        _reauthServer.value = null
        _isAuthenticated.value = true
    }

    /**
     * Re-point the shared client at the active server + token. Call after an Add
     * Server probe (which repoints the client at the candidate server) so the
     * live session keeps working when the sheet closes.
     */
    fun restoreActiveClient() {
        val server = activeServer ?: return
        val token = tokenStore.get(tokenKey(server.id)) ?: return
        gateway.configure(serverUrl = server.url, accessToken = token, userId = server.userId)
    }

    /**
     * Switch the active server. Reauth lesson (SessionManager.swift): when the
     * chosen server's token was dropped by a past expiry, raise [reauthServer]
     * for a prefilled re-auth instead of silently doing nothing.
     */
    fun switchServer(id: String) {
        if (id == _activeServerId.value) return
        val server = _servers.value.firstOrNull { it.id == id } ?: return
        val token = tokenStore.get(tokenKey(server.id))
        if (token == null) {
            _reauthServer.value = server
            return
        }
        _activeServerId.value = id
        saveServers()
        activate(server, token)
    }

    /**
     * Remove a saved server. Removing the active one activates the next;
     * removing the last returns to the signed-out state.
     */
    fun removeServer(id: String) {
        val servers = _servers.value.toMutableList()
        val idx = servers.indexOfFirst { it.id == id }
        if (idx < 0) return
        tokenStore.delete(tokenKey(id))
        servers.removeAt(idx)
        _servers.value = servers

        if (_activeServerId.value == id) {
            val next = servers.firstOrNull()
            val nextToken = next?.let { tokenStore.get(tokenKey(it.id)) }
            if (next != null && nextToken != null) {
                _activeServerId.value = next.id
                saveServers()
                activate(next, nextToken)
            } else {
                _activeServerId.value = null
                saveServers()
                gateway.clearCredentials()
                _serverUrl.value = null
                _currentUser.value = null
                _logoutReason.value = LogoutReason.USER_INITIATED
                _isAuthenticated.value = false
            }
        } else {
            saveServers()
        }
    }

    /**
     * Sign out of the ACTIVE server. Logout lesson (SessionManager.swift): a
     * session expiry KEEPS the entry (only drops the dead token) so the user can
     * re-authenticate; a user-initiated sign-out removes it entirely.
     */
    fun logout(reason: LogoutReason = LogoutReason.USER_INITIATED) {
        val active = _activeServerId.value
        if (active != null) {
            if (reason == LogoutReason.SESSION_EXPIRED) {
                tokenStore.delete(tokenKey(active))
            } else {
                removeServer(active)
            }
        }
        gateway.clearCredentials()
        _serverUrl.value = null
        _currentUser.value = null
        _logoutReason.value = reason
        _isAuthenticated.value = false
    }

    /**
     * 401-only-on-active-server lesson (JellyfinClient.swift): the client fires
     * this for any non-auth 401/403 with the failing request's server URL. Only
     * expire the session when that URL is the active server's — a 401 during an
     * Add Server probe (client briefly repointed) must not nuke the live session.
     */
    private fun onSessionExpired(failingServerUrl: String) {
        val active = activeServer ?: return
        if (normalizedEquals(active.url, failingServerUrl)) {
            scope.launch { logout(reason = LogoutReason.SESSION_EXPIRED) }
        }
    }

    private fun saveServers() {
        serverStore.saveServers(_servers.value)
        serverStore.setActiveServerId(_activeServerId.value)
    }

    private fun normalizedEquals(
        a: String,
        b: String,
    ): Boolean = a.trimEnd('/') == b.trimEnd('/')

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()
}
