package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.ItemsResponse
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One card on a list: what it shows, and the playlist entries behind it. */
data class ListItem(
    val display: DisplayEntry,
    /** The item to draw and open. For a series card, a series built from its S1:E1 entry. */
    val item: BaseItemDto,
    /** The entries (PlaylistItemId) this card stands for; remove takes them all. */
    val entryIds: List<String>,
    /** The library item ids behind those entries, for the duplicate check. */
    val itemIds: List<String>,
    /** Position of this card's first entry in the playlist; move takes it. */
    val firstEntryIndex: Int,
)

/** A list as the viewer can see it. Counts and "new" come only from [items]. */
data class SharedList(
    val id: String,
    val name: String,
    val isPublic: Boolean,
    /** Null for a list made outside Sashimi (no self-share): "Shared list". */
    val ownerId: String?,
    val ownerName: String?,
    val isMine: Boolean,
    val items: List<ListItem>,
    /** Jellyfin's generated playlist picture, when it has one. */
    val imageTag: String?,
) {
    val count: Int get() = items.size
    val displayIds: List<String> get() = items.map { it.display.id }

    /** "From Marge: Christmas Movies"; your own and unattributed lists use the name alone. */
    val rowTitle: String
        get() = if (isMine || ownerName == null) name else "From $ownerName: $name"
}

/** What Add to List did. */
enum class AddResult { ADDED, ALREADY_ON_LIST }

/**
 * Shared lists over the Jellyfin API (shared lists design 2026-10-27,
 * Milestone 2). Lists are playlists; the creator is recorded by a self-share;
 * each user's subscriptions live in DisplayPreferences so they follow the
 * user to every device and platform.
 *
 * Rules that hold here:
 * - Counts and "new" come only from `/Playlists/{id}/Items?userId=`, which
 *   honours parental limits. `PlaylistDto.ItemIds` is never read.
 * - A series is stored as its first regular episode, never as a series id.
 * - A subscribed list that answers 404 is remembered in [missing] and dropped
 *   from the subscriptions the next time they are saved.
 */
class SharedListsService(
    private val client: JellyfinClient,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val missingIds = mutableSetOf<String>()

    /** Subscribed lists the server answered 404 for since the last save. */
    val missing: Set<String> get() = synchronized(missingIds) { missingIds.toSet() }

    @Volatile private var userNames: Pair<String, Map<String, String>>? = null

    private fun me(): String = client.listsUserId()

    // MARK: - Reading

    /** Your lists and everyone's public lists, each with its visible items. */
    suspend fun allLists(): List<SharedList> {
        val uid = me()
        val raw =
            client.listsRequest(
                "GET",
                "/Items",
                listOf("Recursive" to "true", "IncludeItemTypes" to "Playlist", "userId" to uid, "SortBy" to "SortName"),
            )
        val playlists = decodeItems(raw)
        return coroutineScope {
            playlists.map { dto -> async { runCatching404 { build(dto) } } }.awaitAll().filterNotNull()
        }
    }

    /** One list, or null when it is gone or private to someone else (404). */
    suspend fun list(id: String): SharedList? {
        val dto =
            try {
                decodeItem(client.listsRequest("GET", "/Users/${me()}/Items/$id"))
            } catch (e: JellyfinError.HttpError) {
                if (e.statusCode == 404) {
                    markMissing(id)
                    return null
                }
                throw e
            }
        return runCatching404 { build(dto) }.also { if (it == null) markMissing(id) }
    }

    private suspend fun build(dto: BaseItemDto): SharedList =
        coroutineScope {
            val sharing = async { sharing(dto.id) }
            val items = async { items(dto.id) }
            val (isPublic, shares) = sharing.await()
            val ownerId = SharedLists.owner(shares)
            SharedList(
                id = dto.id,
                name = dto.name,
                isPublic = isPublic,
                ownerId = ownerId,
                ownerName = ownerId?.let { userName(it) },
                isMine = ownerId != null && ownerId == me(),
                items = items.await(),
                imageTag = dto.imageTags?.get("Primary"),
            )
        }

    private suspend fun sharing(id: String): Pair<Boolean, List<PlaylistShare>> {
        val dto = json.decodeFromString<PlaylistDto>(client.listsRequest("GET", "/Playlists/$id"))
        return dto.openAccess to dto.shares
    }

    /** The cards a list shows this viewer, after the series and repeat rules. */
    suspend fun items(id: String): List<ListItem> {
        val raw =
            client.listsRequest(
                "GET",
                "/Playlists/$id/Items",
                listOf(
                    "userId" to me(),
                    "Fields" to "PrimaryImageAspectRatio,CommunityRating,OfficialRating,MediaStreams,ParentId",
                    "EnableImageTypes" to "Primary,Backdrop,Thumb",
                    "EnableUserData" to "true",
                ),
            )
        return cards(decodeItems(raw))
    }

    // MARK: - Subscriptions

    suspend fun subscriptions(): List<ListSubscription> = SharedLists.decodeSubscriptions(customPrefs(loadPrefs())[SUBSCRIPTIONS_KEY])

    /**
     * Read, change and write back the subscriptions; the last write wins.
     * Lists in [missing] are pruned on the way. Every other DisplayPreferences
     * field and CustomPrefs key is written back as it was read.
     */
    suspend fun updateSubscriptions(change: (List<ListSubscription>) -> List<ListSubscription>): List<ListSubscription> {
        val prefs = loadPrefs()
        val gone = missing
        val current = SharedLists.decodeSubscriptions(customPrefs(prefs)[SUBSCRIPTIONS_KEY])
        val updated = SharedLists.prune(change(current), gone.toList())
        val newCustom =
            JsonObject(
                customPrefs(prefs).mapValues { (_, v) -> v.toJson() } +
                    (SUBSCRIPTIONS_KEY to JsonPrimitive(SharedLists.encodeSubscriptions(updated))),
            )
        val body = JsonObject(prefs + (CUSTOM_PREFS to newCustom))
        client.listsRequest("POST", "/DisplayPreferences/$PREFS_ID", prefsQuery(), json.encodeToString(body))
        synchronized(missingIds) { missingIds.removeAll(gone) }
        return updated
    }

    suspend fun subscribe(list: SharedList) = updateSubscriptions { SharedLists.subscribe(it, list.id, list.displayIds) }

    suspend fun unsubscribe(id: String) = updateSubscriptions { SharedLists.unsubscribe(it, id) }

    /** Opening a subscribed list: what it shows now has been seen. */
    suspend fun markSeen(list: SharedList) = updateSubscriptions { SharedLists.markSeen(it, list.id, list.displayIds) }

    private suspend fun loadPrefs(): JsonObject =
        json.parseToJsonElement(client.listsRequest("GET", "/DisplayPreferences/$PREFS_ID", prefsQuery())).jsonObject

    private fun prefsQuery() = listOf("userId" to me(), "client" to PREFS_CLIENT)

    private fun customPrefs(prefs: JsonObject): Map<String, String?> =
        (prefs[CUSTOM_PREFS] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content } ?: emptyMap()

    private fun String?.toJson() = if (this == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(this)

    // MARK: - Changing lists (the owner's)

    /**
     * A new list holding [firstItem] (when given), shared with its creator so
     * every viewer can tell who made it. If the self-share fails the list is
     * deleted again and the error thrown: an unattributed list must not be left.
     *
     * The list is created EMPTY and the first title added after the share.
     * Jellyfin 12.4 saves a playlist created with `Ids` again a moment later
     * from its pre-share state, so a share made in between silently vanishes
     * within ~3 s (verified live 2026-10-27; an empty create keeps it).
     */
    suspend fun create(
        name: String,
        isPublic: Boolean,
        firstItem: BaseItemDto? = null,
    ): String {
        val uid = me()
        val first = firstItem?.let { storedId(it) ?: throw NoEpisodesException() }
        val body = CreatePlaylistBody(name = name, ids = emptyList(), userId = uid, mediaType = "Video", isPublic = isPublic)
        val id = json.decodeFromString<CreatedPlaylist>(client.listsRequest("POST", "/Playlists", jsonBody = json.encodeToString(body))).id
        try {
            client.listsRequest("POST", "/Playlists/$id/Users/$uid", jsonBody = """{"CanEdit":true}""")
        } catch (e: Exception) {
            runCatching { client.listsRequest("DELETE", "/Items/$id") }
            throw e
        }
        if (first != null) client.listsRequest("POST", "/Playlists/$id/Items", listOf("ids" to first, "userId" to uid))
        return id
    }

    /** Adds [item] unless it is already there. A series goes in as its first episode. */
    suspend fun add(
        listId: String,
        item: BaseItemDto,
    ): AddResult {
        val stored = storedId(item) ?: throw NoEpisodesException()
        if (contains(items(listId), item, stored)) return AddResult.ALREADY_ON_LIST
        client.listsRequest("POST", "/Playlists/$listId/Items", listOf("ids" to stored, "userId" to me()))
        return AddResult.ADDED
    }

    /** The id Add to List stores for [item]; null for a series with no regular episode. */
    suspend fun storedId(item: BaseItemDto): String? =
        if (item.type == ItemType.SERIES) SharedLists.seriesAnchor(client.getEpisodes(item.id))?.id else item.id

    suspend fun remove(
        listId: String,
        card: ListItem,
    ) {
        client.listsRequest("DELETE", "/Playlists/$listId/Items", listOf("entryIds" to card.entryIds.joinToString(",")))
    }

    /** Moves [card]'s first entry to playlist position [toEntryIndex]. */
    suspend fun move(
        listId: String,
        card: ListItem,
        toEntryIndex: Int,
    ) {
        val entry = card.entryIds.firstOrNull() ?: return
        client.listsRequest("POST", "/Playlists/$listId/Items/$entry/Move/$toEntryIndex")
    }

    suspend fun rename(
        listId: String,
        name: String,
    ) {
        client.listsRequest("POST", "/Playlists/$listId", jsonBody = json.encodeToString(UpdatePlaylistBody(name = name)))
    }

    suspend fun setPublic(
        listId: String,
        isPublic: Boolean,
    ) {
        client.listsRequest("POST", "/Playlists/$listId", jsonBody = json.encodeToString(UpdatePlaylistBody(isPublic = isPublic)))
    }

    suspend fun delete(listId: String) {
        client.listsRequest("DELETE", "/Items/$listId")
    }

    // MARK: - Helpers

    private suspend fun userName(id: String): String? {
        val key = "${client.currentServerUrl}|${me()}"
        val cached = userNames?.takeIf { it.first == key }?.second
        val names =
            cached ?: runCatching {
                json
                    .decodeFromString<List<UserName>>(client.listsRequest("GET", "/Users"))
                    .associate { it.id to it.name }
            }.getOrNull()?.also { userNames = key to it }
        return names?.get(id)
    }

    private fun markMissing(id: String) {
        synchronized(missingIds) { missingIds += id }
    }

    private inline fun <T> runCatching404(block: () -> T): T? =
        try {
            block()
        } catch (e: JellyfinError.HttpError) {
            if (e.statusCode == 404) null else throw e
        }

    private fun decodeItems(raw: String): List<BaseItemDto> = json.decodeFromString<ItemsResponse>(raw).items

    private fun decodeItem(raw: String): BaseItemDto = json.decodeFromString(raw)

    companion object {
        /** The same literals on every platform, so subscriptions sync between them. */
        const val PREFS_ID = "sashimi-lists"
        const val PREFS_CLIENT = "sashimi"
        const val SUBSCRIPTIONS_KEY = "subscriptions"
        private const val CUSTOM_PREFS = "CustomPrefs"

        /** Groups playlist entries into cards: S1:E1 as its series, repeats once. */
        fun cards(entries: List<BaseItemDto>): List<ListItem> {
            val cards = linkedMapOf<String, ListItem>()
            entries.forEachIndexed { index, entry ->
                val display = SharedLists.displayEntry(ListEntry(entry))
                val entryId = entry.playlistItemId ?: entry.id
                val existing = cards[display.id]
                cards[display.id] =
                    existing?.copy(entryIds = existing.entryIds + entryId, itemIds = existing.itemIds + entry.id)
                        ?: ListItem(display, if (display.isSeries) seriesCard(entry) else entry, listOf(entryId), listOf(entry.id), index)
            }
            return cards.values.toList()
        }

        /** Whether [item] is already on a list showing [cards]; [storedId] is what Add would store. */
        fun contains(
            cards: List<ListItem>,
            item: BaseItemDto,
            storedId: String?,
        ): Boolean =
            cards.any { card -> card.display.id == item.id || item.id in card.itemIds || (storedId != null && storedId in card.itemIds) }

        private fun seriesCard(episode: BaseItemDto): BaseItemDto =
            BaseItemDto(
                id = episode.seriesId ?: episode.id,
                name = episode.seriesName ?: episode.name,
                type = ItemType.SERIES,
                imageTags = episode.seriesPrimaryImageTag?.let { mapOf("Primary" to it) },
                productionYear = episode.productionYear,
                officialRating = episode.officialRating,
            )
    }
}

/** Thrown by Add to List for a series without a regular episode to stand for it. */
class NoEpisodesException : Exception("This show has no episodes to add yet.")

@Serializable
private data class PlaylistDto(
    @SerialName("OpenAccess") val openAccess: Boolean = false,
    @SerialName("Shares") val shares: List<PlaylistShare> = emptyList(),
)

@Serializable
private data class CreatePlaylistBody(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
    @SerialName("UserId") val userId: String,
    @SerialName("MediaType") val mediaType: String,
    @SerialName("IsPublic") val isPublic: Boolean,
)

@Serializable
private data class CreatedPlaylist(
    @SerialName("Id") val id: String,
)

@Serializable
private data class UpdatePlaylistBody(
    @SerialName("Name") val name: String? = null,
    @SerialName("IsPublic") val isPublic: Boolean? = null,
)

@Serializable
private data class UserName(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)
