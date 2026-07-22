package dev.bitstorm.sashimi.core.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowStore
import dev.bitstorm.sashimi.core.search.RecentSearchPersistence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed [ServerStore]. The server list is non-secret
 * metadata (URLs, usernames, ids); only tokens go in the encrypted store.
 */
class PrefsServerStore(context: Context) : ServerStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadServers(): List<ServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    override fun saveServers(servers: List<ServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, json.encodeToString(servers)).apply()
    }

    override fun getActiveServerId(): String? = prefs.getString(KEY_ACTIVE, null)

    override fun setActiveServerId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "sashimi_servers"
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE = "activeServerId"
    }
}

/**
 * EncryptedSharedPreferences-backed [TokenStore] — the Android analogue of the
 * Swift Keychain. [save] returns the commit result so a failed write aborts
 * login visibly (see SessionManager.login).
 */
class EncryptedTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun save(
        value: String,
        key: String,
    ): Boolean = prefs.edit().putString(key, value).commit()

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val PREFS_NAME = "sashimi_tokens"
    }
}

/** SharedPreferences-backed [HomeRowStore] — the Home row order/visibility. */
class PrefsHomeRowStore(context: Context) : HomeRowStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<HomeRowConfig>? {
        val raw = prefs.getString(KEY_ROWS, null) ?: return null
        return runCatching { json.decodeFromString<List<HomeRowConfig>>(raw) }.getOrNull()
    }

    override fun save(rows: List<HomeRowConfig>) {
        prefs.edit().putString(KEY_ROWS, json.encodeToString(rows)).apply()
    }

    companion object {
        private const val PREFS_NAME = "sashimi_home_rows"
        private const val KEY_ROWS = "homeRowOrder"
    }
}

/** SharedPreferences-backed [RecentSearchPersistence] — the last-10 queries. */
class PrefsRecentSearchStore(context: Context) : RecentSearchPersistence {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<String> {
        val raw = prefs.getString(KEY_SEARCHES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    override fun save(searches: List<String>) {
        prefs.edit().putString(KEY_SEARCHES, json.encodeToString(searches)).apply()
    }

    companion object {
        private const val PREFS_NAME = "sashimi_recent_searches"
        private const val KEY_SEARCHES = "recentSearches"
    }
}
