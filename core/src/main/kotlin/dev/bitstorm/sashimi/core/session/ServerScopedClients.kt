package dev.bitstorm.sashimi.core.session

/**
 * Picks the client for work that belongs to one saved server: a title opened
 * from another server's filmography, its playback, its downloads, its theme.
 *
 * The shared client follows the active server and is never repointed. Every
 * other server gets a dedicated client from [ServerClientRegistry], built
 * without a session-expiry handler, so a 401 from a non-active server can
 * never sign the user out of the active one.
 */
class ServerScopedClients<C : Any>(
    private val shared: C,
    private val activeServerId: () -> String?,
    private val servers: () -> List<ServerConfig>,
    private val tokenFor: (serverId: String) -> String?,
    factory: (server: ServerConfig, token: String) -> C,
) {
    private val registry = ServerClientRegistry(factory)

    /**
     * For interactive work on [serverId] (the player, theme songs). Null or the
     * active server gets the shared client, keeping its sign-out-on-401
     * behaviour for the active session. Any other server gets its dedicated
     * client. Null when that server is no longer saved or has no token.
     */
    fun clientFor(serverId: String?): C? {
        if (serverId == null || serverId == activeServerId()) return shared
        return dedicated(serverId)
    }

    /**
     * A client bound to [serverId] alone, even when it is the active server.
     * For anything that must keep talking to that server after the user
     * switches: a pinned detail route, or a download record. Null when the
     * server is no longer saved or has no token.
     */
    fun dedicated(serverId: String): C? {
        val server = servers().firstOrNull { it.id == serverId } ?: return null
        val token = tokenFor(serverId) ?: return null
        return registry.clientFor(server, token)
    }

    /**
     * The client for a stored record (a download) stamped with [serverId].
     * Records made before downloads carried a server have a null id and keep
     * their old behaviour: the shared client.
     */
    fun forRecord(serverId: String?): C? = if (serverId == null) shared else dedicated(serverId)

    /** The dedicated client for a server whose config and token the caller already holds. */
    fun dedicated(
        server: ServerConfig,
        token: String,
    ): C = registry.clientFor(server, token)
}
