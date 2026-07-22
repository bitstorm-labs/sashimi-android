package dev.bitstorm.sashimi.core.network

import dev.bitstorm.sashimi.core.model.AuthenticationResult
import dev.bitstorm.sashimi.core.model.PublicSystemInfo

/**
 * The subset of [JellyfinClient] that [dev.bitstorm.sashimi.core.session.SessionManager]
 * drives. Extracted as an interface so the session-manager unit tests can fake
 * the network without a real HTTP stack.
 */
interface JellyfinAuthGateway {
    /** The server URL the client is currently pointed at, or null. */
    val currentServerUrl: String?

    /**
     * Invoked with the client's current server URL when a non-auth request
     * receives a 401/403. SessionManager sets this and gates the actual logout
     * on the URL matching the ACTIVE server (the 401-only-on-active lesson).
     */
    var sessionExpiredHandler: ((serverUrl: String) -> Unit)?

    fun configure(
        serverUrl: String,
        accessToken: String? = null,
        userId: String? = null,
    )

    fun clearCredentials()

    suspend fun authenticate(
        username: String,
        password: String,
    ): AuthenticationResult

    suspend fun getPublicSystemInfo(): PublicSystemInfo
}
