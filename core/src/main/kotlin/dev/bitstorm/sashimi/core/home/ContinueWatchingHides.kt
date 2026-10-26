package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.serialization.Serializable

/**
 * A series the user removed from Continue Watching, hidden while its row entry
 * is still [episodeId]. [onServer] records that the Continue Watching plugin
 * holds the hide too, so dropping it here also deletes it there.
 */
@Serializable
data class HiddenSeries(
    val seriesId: String,
    val episodeId: String,
    val onServer: Boolean = false,
)

/** Persistence seam, keyed by server + user ([ContinueWatchingHides.scopeKey]). */
interface HiddenSeriesStore {
    fun load(scope: String): List<HiddenSeries>

    fun save(
        scope: String,
        entries: List<HiddenSeries>,
    )
}

class InMemoryHiddenSeriesStore : HiddenSeriesStore {
    private val entries = mutableMapOf<String, List<HiddenSeries>>()

    override fun load(scope: String): List<HiddenSeries> = entries[scope].orEmpty()

    override fun save(
        scope: String,
        entries: List<HiddenSeries>,
    ) {
        this.entries[scope] = entries
    }
}

/**
 * The hide rule, shared with every Sashimi client and the plugin: a series stays
 * hidden while its entry in the row is still the stored episode. Once the row
 * offers a different episode of it (watched elsewhere, or a resume moved the
 * user on) the hide is stale and dropped, so the series comes back on its own.
 * A series absent from the row keeps its hide: the plugin may be filtering it,
 * or the row is capped.
 */
object HiddenSeriesRule {
    data class Outcome(
        val visible: List<BaseItemDto>,
        val kept: List<HiddenSeries>,
        val stale: List<HiddenSeries>,
    )

    fun apply(
        row: List<BaseItemDto>,
        hidden: List<HiddenSeries>,
    ): Outcome {
        if (hidden.isEmpty()) return Outcome(row, emptyList(), emptyList())
        val bySeries = hidden.associateBy { it.seriesId }
        val stale = mutableListOf<HiddenSeries>()
        val visible =
            row.filter { item ->
                val entry = item.seriesId?.let { bySeries[it] } ?: return@filter true
                if (entry.episodeId == item.id) {
                    false
                } else {
                    stale += entry
                    true
                }
            }
        return Outcome(visible, hidden - stale.toSet(), stale)
    }
}

/**
 * Remove from Continue Watching: clears a resume position, hides an episode's
 * series from Next Up (on the plugin when the server has it, locally when it
 * 404s), applies the hides to the row, and undoes a removal.
 */
class ContinueWatchingHides(
    private val store: HiddenSeriesStore,
) {
    /** What a removal changed, so [undo] can put it back. */
    data class Receipt(
        val item: BaseItemDto,
        val previousPositionTicks: Long,
        val hidden: HiddenSeries?,
    )

    sealed interface Result {
        data class Removed(
            val receipt: Receipt,
        ) : Result

        /** Nothing took effect; the card will be back on the next refresh. */
        data class Failed(
            val error: Throwable,
        ) : Result
    }

    fun hidden(client: JellyfinClient): List<HiddenSeries> = scopeKey(client)?.let(store::load).orEmpty()

    /** The row with hidden series removed. Stale hides are dropped (and deleted on the server). */
    suspend fun filter(
        client: JellyfinClient,
        row: List<BaseItemDto>,
    ): List<BaseItemDto> {
        val scope = scopeKey(client) ?: return row
        val outcome = HiddenSeriesRule.apply(row, store.load(scope))
        if (outcome.stale.isNotEmpty()) {
            store.save(scope, outcome.kept)
            deleteOnServer(client, outcome.stale)
        }
        return outcome.visible
    }

    suspend fun remove(
        client: JellyfinClient,
        item: BaseItemDto,
    ): Result {
        val position = item.userData?.playbackPositionTicks ?: 0L
        var clearError: Throwable? = null
        if (position > 0) {
            runCatchingCancellable { client.setPlaybackPosition(item.id, 0) }.onFailure { clearError = it }
        }

        val seriesId = item.seriesId
        val scope = scopeKey(client)
        if (item.type != ItemType.EPISODE || seriesId == null || scope == null) {
            return clearError?.let { Result.Failed(it) } ?: Result.Removed(Receipt(item, position, null))
        }

        // Any failure of the plugin call (not only its 404) hides locally: the
        // user asked for the card to go, and the local hide filters the plugin's
        // row as well.
        val onServer = runCatchingCancellable { client.hideFromContinueWatching(item.id) }.getOrDefault(false)
        val entry = HiddenSeries(seriesId = seriesId, episodeId = item.id, onServer = onServer)
        val others = store.load(scope).filterNot { it.seriesId == seriesId }
        store.save(scope, (others + entry).takeLast(MAX_ENTRIES))
        return Result.Removed(Receipt(item, if (clearError == null) position else 0L, entry))
    }

    suspend fun undo(
        client: JellyfinClient,
        receipt: Receipt,
    ) {
        val entry = receipt.hidden
        if (entry != null) {
            scopeKey(client)?.let { scope -> store.save(scope, store.load(scope) - entry) }
            if (entry.onServer) runCatchingCancellable { client.unhideFromContinueWatching(entry.episodeId) }
        }
        if (receipt.previousPositionTicks > 0) {
            client.setPlaybackPosition(receipt.item.id, receipt.previousPositionTicks)
        }
    }

    /** Playing any episode of a hidden series on this device brings it back. */
    suspend fun onPlaybackStarted(
        client: JellyfinClient,
        item: BaseItemDto,
    ) {
        val seriesId = item.seriesId ?: return
        val scope = scopeKey(client) ?: return
        val entries = store.load(scope)
        val dropped = entries.filter { it.seriesId == seriesId }
        if (dropped.isEmpty()) return
        store.save(scope, entries - dropped.toSet())
        deleteOnServer(client, dropped)
    }

    private suspend fun deleteOnServer(
        client: JellyfinClient,
        entries: List<HiddenSeries>,
    ) {
        // Best effort: the plugin prunes a stale hide itself on its next GET.
        for (entry in entries.filter { it.onServer }) {
            runCatchingCancellable { client.unhideFromContinueWatching(entry.episodeId) }
        }
    }

    companion object {
        const val MAX_ENTRIES = 500

        fun scopeKey(client: JellyfinClient): String? {
            val server = client.currentServerUrl ?: return null
            val user = client.currentUserId ?: return null
            return "$server|$user"
        }
    }
}
