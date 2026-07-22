package dev.bitstorm.sashimi.core.session

/**
 * Persistence for the saved-server list + active-server pointer. The real
 * Android impl is SharedPreferences-backed; tests use an in-memory fake. Kept
 * as an interface so the session recover/reauth logic can be unit-tested
 * without Android.
 */
interface ServerStore {
    fun loadServers(): List<ServerConfig>

    fun saveServers(servers: List<ServerConfig>)

    fun getActiveServerId(): String?

    fun setActiveServerId(id: String?)
}

/**
 * Secure per-server token storage. Mirrors the Swift KeychainHelper contract:
 * [save] returns false when the write fails (login then aborts visibly instead
 * of stranding a session that vanishes next launch). The real impl is backed by
 * EncryptedSharedPreferences.
 */
interface TokenStore {
    fun get(key: String): String?

    fun save(
        value: String,
        key: String,
    ): Boolean

    fun delete(key: String)
}
