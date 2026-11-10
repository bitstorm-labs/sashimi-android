package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemsResponse
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What linking a Plex account to the Watchlist came to. */
sealed interface PlexLinkResult {
    data class Linked(
        val link: PlexLink,
    ) : PlexLinkResult

    /** plex.tv refused the token (the plugin's 422). */
    data object Rejected : PlexLinkResult

    /** The plugin couldn't reach plex.tv (a 5xx): not the token's fault, try again. */
    data object PlexUnreachable : PlexLinkResult

    /** Already linked to [username]; the token belongs to [newUsername]. */
    data class ConflictsWith(
        val username: String,
        val newUsername: String,
    ) : PlexLinkResult
}

/** `POST /SashimiLists/Watchlist/Items`: the stored entry, and false when it was already there. */
@Serializable
data class WatchlistAdd(
    val entry: WatchlistEntry,
    val added: Boolean,
)

@Serializable
private data class PlexConflict(
    val username: String = "",
    val newUsername: String = "",
)

/**
 * The signed-in user's Watchlist, from the Sashimi Lists plugin 0.2.0+
 * (`/SashimiLists/Watchlist`). The plugin is optional: only a 404 means "no
 * Watchlist on this server". A network error or a 5xx is an error, never absence.
 */
class WatchlistService(
    private val client: JellyfinClient,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    /** Server|user → whether the plugin answered. Only real answers (200 or 404) are cached. */
    private val availability = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Which server and user the client is signed in as now. */
    internal fun sessionKey(): String = "${client.currentServerUrl}|${client.currentUserId}"

    private fun key(): String = sessionKey()

    /** Null only when the server has no Watchlist (404). Anything else that fails throws. */
    suspend fun view(): WatchlistView? {
        val key = key()
        val body =
            try {
                client.listsRequest("GET", "/SashimiLists/Watchlist")
            } catch (e: JellyfinError.HttpError) {
                if (e.statusCode == 404) {
                    availability[key] = false
                    return null
                }
                throw e
            }
        val view =
            try {
                json.decodeFromString<WatchlistView>(body)
            } catch (e: Exception) {
                throw JellyfinError.DecodingError
            }
        availability[key] = true
        return view
    }

    /** [view] as a [WatchlistRead]: never throws, except to cancel. */
    suspend fun read(): WatchlistRead =
        try {
            view()?.let { WatchlistRead.Ready(it) } ?: WatchlistRead.Absent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WatchlistRead.Failed(e)
        }

    /**
     * Whether this server has a Watchlist. False only on a 404: a network
     * error or a 5xx answers true (the Watchlist is offered, and acting on it
     * shows the error) and is asked again next time.
     */
    suspend fun available(): Boolean {
        availability[key()]?.let { return it }
        return read() !is WatchlistRead.Absent
    }

    /** The on-server entries' library items, keyed by [SharedLists.normalizeId]. */
    suspend fun libraryItems(ids: List<String>): Map<String, BaseItemDto> =
        // Batches keep the request line short: a synced Plex watchlist can hold hundreds of titles.
        ids.chunked(LIBRARY_BATCH).fold(emptyMap()) { acc, batch -> acc + libraryBatch(batch) }

    private suspend fun libraryBatch(ids: List<String>): Map<String, BaseItemDto> {
        if (ids.isEmpty()) return emptyMap()
        val body =
            client.listsRequest(
                "GET",
                "/Items",
                listOf(
                    "Ids" to ids.joinToString(","),
                    "userId" to client.listsUserId(),
                    "Fields" to "PrimaryImageAspectRatio,ProviderIds",
                    "EnableUserData" to "true",
                    "EnableImageTypes" to "Primary",
                ),
            )
        val items =
            try {
                json.decodeFromString<ItemsResponse>(body).items
            } catch (e: Exception) {
                throw JellyfinError.DecodingError
            }
        return items.associateBy { SharedLists.normalizeId(it.id) }
    }

    suspend fun add(entry: NewWatchlistEntry): WatchlistAdd {
        val body = client.listsRequest("POST", "/SashimiLists/Watchlist/Items", jsonBody = json.encodeToString(entry))
        return try {
            json.decodeFromString<WatchlistAdd>(body)
        } catch (e: Exception) {
            throw JellyfinError.DecodingError
        }
    }

    suspend fun remove(entryId: String) {
        client.listsRequest("DELETE", "/SashimiLists/Watchlist/Items/$entryId")
    }

    /**
     * Hands a plex.tv token to the plugin. 422 is Plex refusing it; 409 means
     * another Plex account is linked. The token is never in an error: only the status is.
     */
    suspend fun linkPlex(
        token: String,
        replace: Boolean,
    ): PlexLinkResult {
        val body =
            buildJsonObject {
                put("token", token)
                put("replace", replace)
            }.toString()
        val reply = client.listsExchange("POST", "/SashimiLists/Watchlist/Plex", jsonBody = body)
        return when (reply.code) {
            in 200..299 -> PlexLinkResult.Linked(decode<PlexLink>(reply.body))
            409 -> decode<PlexConflict>(reply.body).let { PlexLinkResult.ConflictsWith(it.username, it.newUsername) }
            422 -> PlexLinkResult.Rejected
            in 500..599 -> PlexLinkResult.PlexUnreachable
            else -> throw JellyfinError.HttpError(reply.code)
        }
    }

    suspend fun unlinkPlex() {
        client.listsRequest("DELETE", "/SashimiLists/Watchlist/Plex")
    }

    suspend fun dismissCard() {
        client.listsRequest("POST", "/SashimiLists/Watchlist/DismissCard")
    }

    // MARK: - Watchlist v2 (plugin 0.3.0)

    /** Server|user → whether the plugin has the v2 routes. Only real answers (200 or 404) are cached. */
    private val v2 = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** A GET on a v2 route: null on a 404 (the plugin is missing or older than 0.3.0). */
    private suspend fun v2Get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): String? {
        val key = key()
        return try {
            client.listsRequest("GET", path, query).also { v2[key] = true }
        } catch (e: JellyfinError.HttpError) {
            if (e.statusCode == 404 && path == CONFIG) v2[key] = false
            if (e.statusCode == 404) null else throw e
        }
    }

    /**
     * The server's settings for the apps, null when the plugin is too old.
     * Failures throw: the caller treats them like null (the address box stays).
     */
    suspend fun config(): SashimiServerConfig? = v2Get(CONFIG)?.let { decode<SashimiServerConfig>(it) }

    /**
     * Whether the server has Watchlist v2 (＋ Add, the details page). Asks
     * once per server and user; a failure answers false and is asked again.
     */
    suspend fun v2Available(): Boolean {
        v2[key()]?.let { return it }
        return try {
            config() != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** ＋ Add's search; null when the plugin is too old. [text] is trimmed and must be [WatchlistV2.searchable]. */
    suspend fun search(text: String): WatchlistSearchResult? =
        v2Get("/SashimiLists/Watchlist/Search", listOf("q" to text.trim()))?.let { decode<WatchlistSearchResult>(it) }

    /** A title not on the server, from the server's metadata providers. Null: no provider knows it, or the plugin is too old. */
    suspend fun details(ref: WatchlistTitleRef): WatchlistTitleDetails? =
        v2Get("/SashimiLists/Watchlist/Details", ref.query())?.let { decode<WatchlistTitleDetails>(it) }

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw JellyfinError.DecodingError
        }

    private companion object {
        const val LIBRARY_BATCH = 100
        const val CONFIG = "/SashimiLists/Config"
    }
}
