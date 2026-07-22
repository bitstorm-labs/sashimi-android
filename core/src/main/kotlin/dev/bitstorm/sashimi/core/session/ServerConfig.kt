package dev.bitstorm.sashimi.core.session

import kotlinx.serialization.Serializable

/**
 * A saved Jellyfin server + account. Ported from the Swift `ServerConfig`.
 * Tokens live in the encrypted token store under "accessToken.<id>"; everything
 * else persists as JSON in the (unencrypted) server store.
 */
@Serializable
data class ServerConfig(
    val id: String,
    var name: String,
    val url: String,
    val username: String,
    val userId: String,
)

enum class LogoutReason {
    USER_INITIATED,
    SESSION_EXPIRED,
}

/** Errors surfaced by the SessionManager. Ported from Swift `SessionError`. */
sealed class SessionError(message: String) : Exception(message) {
    /** The token store rejected the access-token write. Login aborts so the
     * failure is visible now rather than a silent sign-out on next launch. */
    object CredentialStorageFailed :
        SessionError("Could not save credentials securely. Please try signing in again.")

    /** Same server URL + user already saved (with a live session). */
    object DuplicateServer :
        SessionError("That server and user are already added.")
}
