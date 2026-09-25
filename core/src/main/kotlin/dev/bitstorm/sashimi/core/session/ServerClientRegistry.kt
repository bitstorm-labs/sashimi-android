package dev.bitstorm.sashimi.core.session

/**
 * One client instance per saved server, for work that targets a server other
 * than (or independently of) the active one: the cross-server filmography, a
 * detail route opened from it, and that title's playback, downloads and theme.
 * See [ServerScopedClients] for which client a given piece of work gets.
 *
 * The shared client is never repointed. A cached client is also never
 * reconfigured in place, because a screen may still be holding it: when a
 * server's URL, user or token changes, a new instance replaces the old one and
 * the old one keeps working against what it was built for until it is dropped.
 */
class ServerClientRegistry<C : Any>(
    private val factory: (server: ServerConfig, token: String) -> C,
) {
    private data class Entry<C>(
        val url: String,
        val userId: String,
        val token: String,
        val client: C,
    )

    private val entries = HashMap<String, Entry<C>>()

    @Synchronized
    fun clientFor(
        server: ServerConfig,
        token: String,
    ): C {
        val cached = entries[server.id]
        if (cached != null && cached.url == server.url && cached.userId == server.userId && cached.token == token) {
            return cached.client
        }
        val client = factory(server, token)
        entries[server.id] = Entry(server.url, server.userId, token, client)
        return client
    }
}
