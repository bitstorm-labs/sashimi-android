package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.home.ContinueWatchingMerger
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * "Keep next episodes downloaded", ported from sashimi-apple
 * (SashimiMobile/Downloads/Services/KeepNextEpisodes*). Labels and rules are
 * shared with the Apple client word for word.
 */
object KeepNextEpisodesOption {
    /** The choices offered; 0 is Off. */
    val COUNTS = listOf(1, 3, 5)

    /** "Keeping next 3". */
    fun keepingTitle(count: Int): String = "Keeping next $count"

    fun countTitle(count: Int): String = if (count == 1) "Next episode" else "Next $count episodes"

    fun menuTitle(count: Int): String = if (count > 0) "Keep Next Episodes Downloaded: $count" else "Keep Next Episodes Downloaded"

    fun confirmation(count: Int): String = "Keeping the next ${if (count == 1) "episode" else "$count episodes"} downloaded"
}

/**
 * The pure rule: given a show's episodes in order and what is on the device,
 * which downloads to delete and which episodes to queue so the next `count`
 * unwatched episodes stay downloaded.
 */
object KeepNextEpisodesPlanner {
    /** Played to at least this fraction offline counts as watched (Apple DownloadWatchPolicy). */
    const val PLAYED_FRACTION = 0.9

    data class Episode(
        val id: String,
        /** Season 0. Specials are never part of the window. */
        val isSpecial: Boolean,
        val isPlayed: Boolean,
        /** When the server last saw it played, epoch millis. */
        val lastPlayedAt: Long?,
    )

    data class Download(
        val itemId: String,
        val dateAdded: Long,
        /** Played to the end offline, so the server may not know yet. */
        val isWatchedLocally: Boolean,
    )

    data class Plan(
        /** Downloads of episodes watched since they were downloaded. */
        val delete: List<String> = emptyList(),
        /** Episodes in the window with no download record, in order. */
        val enqueue: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = delete.isEmpty() && enqueue.isEmpty()
    }

    fun plan(
        episodes: List<Episode>,
        downloads: List<Download>,
        count: Int,
    ): Plan {
        if (count <= 0) return Plan()
        val byId = downloads.groupBy { it.itemId }.mapValues { it.value.first() }

        fun isWatched(episode: Episode) = episode.isPlayed || byId[episode.id]?.isWatchedLocally == true

        // The viewer's position: just after the last watched regular episode,
        // as Jellyfin's Next Up places it. An in-progress episode sits there.
        val regular = episodes.filterNot { it.isSpecial }
        // Nothing after the last watched episode is watched, so the window is
        // simply the next [count] from there.
        val start = regular.indexOfLast(::isWatched) + 1
        val window = regular.drop(start).take(count)

        // Downloaded, queued and even failed episodes count toward the window;
        // a failed one is left for the user to retry.
        val enqueue = window.map { it.id }.filter { it !in byId }
        val delete =
            episodes.mapNotNull { episode ->
                val download = byId[episode.id] ?: return@mapNotNull null
                episode.id.takeIf { watchedSinceDownloaded(episode, download) }
            }
        return Plan(delete = delete, enqueue = enqueue)
    }

    /**
     * Only an episode finished after it was downloaded is removed. One
     * downloaded deliberately after watching it (to rewatch) stays, as does a
     * played episode with no date to compare.
     */
    fun watchedSinceDownloaded(
        episode: Episode,
        download: Download,
    ): Boolean {
        if (download.isWatchedLocally) return true
        val lastPlayed = episode.lastPlayedAt ?: return false
        return episode.isPlayed && lastPlayed > download.dateAdded
    }

    fun episode(dto: BaseItemDto) =
        Episode(
            id = dto.id,
            isSpecial = BulkDownloadPlanner.isSpecial(dto),
            isPlayed = dto.userData?.played == true,
            lastPlayedAt = ContinueWatchingMerger.parseDate(dto.userData?.lastPlayedDate),
        )

    fun download(row: DownloadedItemEntity): Download {
        val runTime = row.runTimeTicks ?: 0L
        val watched = runTime > 0 && row.localPositionTicks.toDouble() / runTime >= PLAYED_FRACTION
        return Download(itemId = row.itemId, dateAdded = row.dateAdded, isWatchedLocally = watched)
    }
}

/** One show's setting. */
@Serializable
data class KeepNextEpisodesSetting(
    val serverId: String,
    val seriesId: String,
    val count: Int,
    /** Quality for the episodes this setting queues ([DownloadQuality.wireName]). */
    val quality: String,
) {
    val downloadQuality: DownloadQuality get() = DownloadQuality.fromWire(quality)
}

interface KeepNextEpisodesPersistence {
    fun load(): List<KeepNextEpisodesSetting>

    fun save(settings: List<KeepNextEpisodesSetting>)
}

class InMemoryKeepNextEpisodesPersistence : KeepNextEpisodesPersistence {
    private var settings = emptyList<KeepNextEpisodesSetting>()

    override fun load() = settings

    override fun save(settings: List<KeepNextEpisodesSetting>) {
        this.settings = settings
    }
}

/** The per-show settings, keyed by server and series. The setting outlives the show's downloads. */
class KeepNextEpisodesStore(
    private val persistence: KeepNextEpisodesPersistence,
) {
    private val _settings = MutableStateFlow(persistence.load().associateBy { key(it.serverId, it.seriesId) })
    val settings: StateFlow<Map<String, KeepNextEpisodesSetting>> = _settings.asStateFlow()

    fun setting(
        serverId: String?,
        seriesId: String,
    ): KeepNextEpisodesSetting? = serverId?.let { _settings.value[key(it, seriesId)] }

    /** The kept count for a show, 0 when off. */
    fun count(
        serverId: String?,
        seriesId: String,
    ): Int = setting(serverId, seriesId)?.count ?: 0

    /**
     * Turns the setting on (count > 0) or off (0). Quality is kept from an
     * existing setting when not given.
     */
    fun set(
        count: Int,
        serverId: String,
        seriesId: String,
        quality: DownloadQuality? = null,
    ) {
        val key = key(serverId, seriesId)
        _settings.update { current ->
            if (count <= 0) {
                current - key
            } else {
                val wire = quality?.wireName ?: current[key]?.quality ?: DownloadQuality.HIGH.wireName
                current + (key to KeepNextEpisodesSetting(serverId, seriesId, count, wire))
            }
        }
        persistence.save(_settings.value.values.toList())
    }

    val active: List<KeepNextEpisodesSetting>
        get() = _settings.value.values.sortedBy { it.seriesId }

    companion object {
        fun key(
            serverId: String,
            seriesId: String,
        ) = "$serverId|$seriesId"
    }
}

/**
 * Keeps each opted-in show's next episodes downloaded: deletes the episodes
 * watched since they were downloaded and queues the next unwatched ones.
 * Debounced; the app triggers it on foreground, when the network comes back,
 * after a player closes and after a download completes. Never offline and
 * never while a player is open.
 */
class KeepNextEpisodesSync(
    private val store: KeepNextEpisodesStore,
    private val clientFor: (serverId: String) -> JellyfinClient?,
    private val downloads: () -> List<DownloadedItemEntity>,
    private val enqueue: (List<BaseItemDto>, DownloadQuality, String) -> Unit,
    private val delete: (DownloadKey) -> Unit,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val players = MutableStateFlow<Set<Any>>(emptySet())
    private val mutex = Mutex()
    private var pending: Job? = null

    fun playerOpened(owner: Any) = players.update { it + owner }

    /** A closed player is a trigger: what was just watched may now be deletable. */
    fun playerClosed(owner: Any): Job? {
        players.update { it - owner }
        return scheduleSync()
    }

    /** Debounced: a burst of triggers runs one sync. Returns the scheduled run, null when nothing is kept. */
    @Synchronized
    fun scheduleSync(delayMs: Long = debounceMs): Job? {
        if (store.active.isEmpty()) return null
        pending?.cancel()
        return scope
            .launch {
                delay(delayMs)
                runSync()
            }.also { pending = it }
    }

    /** One pass over every active setting. Public for tests. */
    suspend fun runSync() =
        mutex.withLock {
            for (setting in store.active) {
                // Re-checked per show: playback or a lost connection mid-run stops it.
                if (!isOnline() || players.value.isNotEmpty()) return@withLock
                runCatchingCancellable { sync(setting) }
            }
        }

    private suspend fun sync(setting: KeepNextEpisodesSetting) {
        val client = clientFor(setting.serverId) ?: return
        val episodes = client.getEpisodes(setting.seriesId)
        // The setting may have changed while the episodes loaded.
        val current = store.setting(setting.serverId, setting.seriesId) ?: return
        val records = downloads().filter { it.serverId == setting.serverId && it.seriesId == setting.seriesId }
        val plan =
            KeepNextEpisodesPlanner.plan(
                episodes = episodes.map(KeepNextEpisodesPlanner::episode),
                downloads = records.map(KeepNextEpisodesPlanner::download),
                count = current.count,
            )
        if (plan.isEmpty) return
        plan.delete.forEach { delete(DownloadKey.of(setting.serverId, it)) }
        val byId = episodes.associateBy { it.id }
        val queue = plan.enqueue.mapNotNull(byId::get)
        if (queue.isNotEmpty()) enqueue(queue, current.downloadQuality, setting.serverId)
    }

    companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
