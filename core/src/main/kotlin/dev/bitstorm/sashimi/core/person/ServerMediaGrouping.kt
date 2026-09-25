package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import java.util.Locale

/**
 * An item together with the saved server that returned it. Item ids only mean
 * something within one server, so the server id is part of the identity
 * everywhere a cross-server result is rendered or opened.
 */
data class ServerMediaResult(
    val item: BaseItemDto,
    val serverId: String,
    val serverName: String,
) {
    val id: String get() = "$serverId:${item.id}"
}

/** One de-duplicated title with at most one source per server. Never empty. */
data class ServerMediaGroup(
    val key: String,
    val sources: List<ServerMediaResult>,
) {
    val primary: ServerMediaResult get() = sources.first()
}

/** Port of sashimi-apple `ServerMediaResultGrouping`. */
object ServerMediaGrouping {
    /**
     * Presentation identity of a title: type + year + folded name. Server and
     * item ids are deliberately excluded so copies on different servers collapse.
     */
    fun titleKey(item: BaseItemDto): String {
        val type = item.type?.wireName ?: ItemType.UNKNOWN.wireName
        val year = item.displayYear?.toString().orEmpty()
        return "$type|$year|${NameKey.of(item.name)}"
    }

    /**
     * Groups [results] by [titleKey], keeping one source per server (the better
     * copy when a server has the title twice). Groups keep the order in which
     * their key first appeared. Within a group, [preferredServerId] leads and the
     * rest follow [serverOrder] (the saved-server list), then name.
     */
    fun groups(
        results: List<ServerMediaResult>,
        preferredServerId: String? = null,
        serverOrder: List<String> = emptyList(),
    ): List<ServerMediaGroup> {
        val buckets = LinkedHashMap<String, MutableList<ServerMediaResult>>()
        results.forEach { buckets.getOrPut(titleKey(it.item)) { mutableListOf() }.add(it) }
        return buckets.map { (key, bucket) ->
            val bestByServer = LinkedHashMap<String, ServerMediaResult>()
            bucket.forEach { candidate ->
                val current = bestByServer[candidate.serverId]
                if (current == null || isBetter(candidate, current)) bestByServer[candidate.serverId] = candidate
            }
            ServerMediaGroup(key, orderSources(bestByServer.values.toList(), preferredServerId, serverOrder))
        }
    }

    fun orderSources(
        sources: List<ServerMediaResult>,
        preferredServerId: String?,
        serverOrder: List<String>,
    ): List<ServerMediaResult> =
        sources.sortedWith(
            compareBy<ServerMediaResult> { if (preferredServerId != null && it.serverId == preferredServerId) 0 else 1 }
                .thenBy { serverOrder.indexOf(it.serverId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.serverName.lowercase(Locale.ROOT) },
        )

    /** Higher resolution wins, then higher community rating, then the lower id (stable). */
    internal fun isBetter(
        candidate: ServerMediaResult,
        current: ServerMediaResult,
    ): Boolean {
        val q = qualityRank(candidate.item.qualityBadge).compareTo(qualityRank(current.item.qualityBadge))
        if (q != 0) return q > 0
        val r = (candidate.item.communityRating ?: 0.0).compareTo(current.item.communityRating ?: 0.0)
        if (r != 0) return r > 0
        return candidate.item.id < current.item.id
    }

    private fun qualityRank(badge: String?): Int =
        when (badge) {
            "4K" -> 3
            "HD" -> 2
            "SD" -> 1
            else -> 0
        }
}

/** Turns raw per-server person credits into the rows the person screen shows. */
object Filmography {
    /**
     * Keeps movies and series only, drops the title the user came from (on every
     * server, by [excludeTitleKey], and the exact origin item by id), and removes
     * exact duplicate results.
     */
    fun visible(
        results: List<ServerMediaResult>,
        excludeTitleKey: String? = null,
        excludeItemId: String? = null,
        excludeServerId: String? = null,
    ): List<ServerMediaResult> {
        val seen = HashSet<String>()
        return results.filter { result ->
            val type = result.item.type
            if (type != ItemType.MOVIE && type != ItemType.SERIES) return@filter false
            if (excludeTitleKey != null && ServerMediaGrouping.titleKey(result.item) == excludeTitleKey) return@filter false
            if (excludeItemId != null && result.item.id == excludeItemId && result.serverId == excludeServerId) return@filter false
            seen.add(result.id)
        }
    }

    /**
     * visible -> grouped -> sorted by title (folded, so "Élan" sits with "E"),
     * then year, then type. The sort makes the merged list independent of which
     * server answered first.
     */
    fun build(
        results: List<ServerMediaResult>,
        preferredServerId: String?,
        serverOrder: List<String>,
        excludeTitleKey: String? = null,
        excludeItemId: String? = null,
    ): List<ServerMediaGroup> =
        ServerMediaGrouping
            .groups(
                visible(results, excludeTitleKey, excludeItemId, preferredServerId),
                preferredServerId,
                serverOrder,
            ).sortedWith(
                compareBy<ServerMediaGroup> { NameKey.of(it.primary.item.name) }
                    .thenBy { it.primary.item.displayYear ?: Int.MAX_VALUE }
                    .thenBy { it.primary.item.type?.wireName.orEmpty() },
            )
}
