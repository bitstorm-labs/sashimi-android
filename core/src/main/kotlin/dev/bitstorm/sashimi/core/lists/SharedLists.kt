package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A playlist share as Jellyfin reports it (`GET /Playlists/{id}` → `Shares`). */
@Serializable
data class PlaylistShare(
    @SerialName("UserId") val userId: String,
    @SerialName("CanEdit") val canEdit: Boolean = false,
)

/** One entry of a list, reduced to what the display rules need. */
data class ListEntry(
    val itemId: String,
    val type: ItemType?,
    val seriesId: String?,
    val season: Int?,
    val episode: Int?,
) {
    constructor(item: BaseItemDto) : this(item.id, item.type, item.seriesId, item.parentIndexNumber, item.indexNumber)
}

/** What a list shows: an item, or the series an S1:E1 entry stands for. */
data class DisplayEntry(
    val id: String,
    val isSeries: Boolean,
)

/** Who made a list, as the Sashimi Lists server plugin records it. */
@Serializable
data class ListOwner(
    @SerialName("OwnerUserId") val userId: String,
    @SerialName("OwnerName") val name: String? = null,
)

/** How a list's cover is drawn. */
sealed interface ListCover {
    /** Four different titles, 2x2, in list order (left to right, then down). */
    data class Collage(
        val tiles: List<BaseItemDto>,
    ) : ListCover

    /** Fewer than four different titles: the first one's poster, full-bleed. */
    data class Single(
        val item: BaseItemDto,
    ) : ListCover

    /** Nothing the viewer can see. */
    data object Empty : ListCover
}

/** One subscribed list and the titles the subscriber had seen on it. */
@Serializable
data class ListSubscription(
    val id: String,
    val seen: List<String> = emptyList(),
)

/**
 * The rules every Sashimi client shares for lists (shared lists design,
 * 2026-10-27). Pure, so the same test vectors run on Apple, Android and Roku.
 */
object SharedLists {
    private val json = Json { ignoreUnknownKeys = true }

    /** Jellyfin does not report a playlist's owner; Sashimi shares each list with its creator. */
    fun owner(shares: List<PlaylistShare>): String? = shares.firstOrNull { it.canEdit }?.userId

    /**
     * A list's cover, from its cards in display order (after the S1:E1 and
     * repeat rules). Two cards from the same show are the same title: an
     * episode card counts as its series, so the collage never repeats a poster.
     */
    fun cover(cards: List<ListItem>): ListCover {
        val titles = cards.distinctBy { titleKey(it) }
        return when {
            titles.size >= 4 -> ListCover.Collage(titles.take(4).map { it.item })
            titles.isNotEmpty() -> ListCover.Single(titles.first().item)
            else -> ListCover.Empty
        }
    }

    private fun titleKey(card: ListItem): String = card.item.seriesId?.takeIf { card.item.type == ItemType.EPISODE } ?: card.display.id

    /** Jellyfin ids with or without dashes, any case, compare equal. */
    fun sameId(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && normalizeId(a) == normalizeId(b)

    fun normalizeId(id: String): String = id.replace("-", "").lowercase()

    /** S1:E1 stands for its series (adding a series adds every episode); repeats show once. */
    fun displayEntries(entries: List<ListEntry>): List<DisplayEntry> = entries.map(::displayEntry).distinctBy { it.id }

    /** What one entry shows as, before repeats collapse. */
    fun displayEntry(entry: ListEntry): DisplayEntry =
        if (entry.type == ItemType.EPISODE && entry.season == 1 && entry.episode == 1 && entry.seriesId != null) {
            DisplayEntry(entry.seriesId, true)
        } else {
            DisplayEntry(entry.itemId, false)
        }

    /** Anything unreadable is no subscriptions; entries without an id are dropped, a repeat keeps the first. */
    fun decodeSubscriptions(raw: String?): List<ListSubscription> {
        val array = runCatching { json.parseToJsonElement(raw ?: return emptyList()) as? JsonArray }.getOrNull() ?: return emptyList()
        return array
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val seen = (obj["seen"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                ListSubscription(id, seen)
            }.distinctBy { it.id }
    }

    fun encodeSubscriptions(subs: List<ListSubscription>): String = json.encodeToString(subs)

    fun newCount(
        displayIds: List<String>,
        seen: List<String>,
    ): Int {
        val seenSet = seen.toSet()
        return displayIds.count { it !in seenSet }
    }

    /** A new subscription has seen everything on the list, so it starts at 0 new. */
    fun subscribe(
        subs: List<ListSubscription>,
        id: String,
        current: List<String>,
    ): List<ListSubscription> = if (subs.any { it.id == id }) subs else subs + ListSubscription(id, current)

    fun unsubscribe(
        subs: List<ListSubscription>,
        id: String,
    ): List<ListSubscription> = subs.filter { it.id != id }

    fun markSeen(
        subs: List<ListSubscription>,
        id: String,
        current: List<String>,
    ): List<ListSubscription> = subs.map { if (it.id == id) ListSubscription(id, current) else it }

    /** Drops lists the server no longer shows this user (deleted or private). */
    fun prune(
        subs: List<ListSubscription>,
        missing: List<String>,
    ): List<ListSubscription> {
        val gone = missing.toSet()
        return subs.filter { it.id !in gone }
    }

    /**
     * The episode "Add to List" stores for a series: the lowest season >= 1,
     * then its lowest episode. Adding the series id itself would make the
     * server add every episode. Null when the show has no regular episode yet.
     */
    fun seriesAnchor(episodes: List<BaseItemDto>): BaseItemDto? =
        episodes
            .filter { it.type == ItemType.EPISODE && (it.parentIndexNumber ?: 0) >= 1 && it.indexNumber != null }
            .minWithOrNull(compareBy({ it.parentIndexNumber }, { it.indexNumber }))

    /** A new subscription's row goes after Continue Watching, else after the hero, else first. */
    fun insertionIndex(
        rowKeys: List<String>,
        continueWatchingKey: String,
        heroKey: String,
    ): Int {
        val cw = rowKeys.indexOf(continueWatchingKey)
        if (cw >= 0) return cw + 1
        val hero = rowKeys.indexOf(heroKey)
        return if (hero >= 0) hero + 1 else 0
    }
}
