package dev.bitstorm.sashimi.core.lists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings › Plex: the Watchlist's Plex link. */
sealed interface PlexSetting {
    data object Loading : PlexSetting

    data object NotConnected : PlexSetting

    data class Connected(
        val username: String,
        val needsReconnect: Boolean,
    ) : PlexSetting

    /** The Watchlist couldn't be read (network, 5xx): the row stays, and acting on it shows any error. */
    data object Unknown : PlexSetting

    companion object {
        /** What the row shows for [read]; null hides it. */
        fun of(read: WatchlistRead): PlexSetting? =
            when (read) {
                WatchlistRead.Pending -> Loading
                WatchlistRead.Absent -> null
                is WatchlistRead.Failed -> Unknown
                is WatchlistRead.Ready -> read.view.plex?.let { Connected(it.username, it.needsReconnect) } ?: NotConnected
            }
    }
}

/**
 * The Settings › Plex row's state. Null hides the row: the server has no
 * Watchlist (404). Re-reads whenever [WatchlistPlexLinker.changes] fires, so a
 * link made or dropped on the Watchlist screen shows here too.
 */
class WatchlistPlexStatus(
    private val service: WatchlistService,
    private val linker: WatchlistPlexLinker,
    private val scope: CoroutineScope,
) {
    private val _setting = MutableStateFlow<PlexSetting?>(PlexSetting.Loading)
    val setting: StateFlow<PlexSetting?> = _setting.asStateFlow()

    init {
        scope.launch { linker.changes.collect { refresh() } }
    }

    /** Re-reads the link; keeps what shows until the answer comes. */
    fun refresh(): Job = scope.launch { _setting.value = PlexSetting.of(service.read()) }
}
