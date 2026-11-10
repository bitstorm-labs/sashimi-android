package dev.bitstorm.sashimi.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.lists.Watchlist
import dev.bitstorm.sashimi.core.lists.WatchlistEntry
import dev.bitstorm.sashimi.core.lists.WatchlistPlexLinker
import dev.bitstorm.sashimi.core.lists.WatchlistService
import dev.bitstorm.sashimi.core.lists.WatchlistView
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrSession
import dev.bitstorm.sashimi.core.util.ResumeRefreshGate
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val view: WatchlistView? = null,
    /** On-server entries' library items, keyed by normalized id. */
    val items: Map<String, BaseItemDto> = emptyMap(),
    /** Seerr's word for not-on-server entries, by entry id. Absent: no session or no answer. */
    val badges: Map<String, SeerrBadge> = emptyMap(),
    /** The server answered 404: no Watchlist (plugin removed or too old). */
    val gone: Boolean = false,
)

/** The Watchlist screen: its entries, the Plex card and link, Remove a Title. */
class WatchlistViewModel(
    private val service: WatchlistService = ServiceLocator.watchlist,
    private val seerr: SeerrSession = ServiceLocator.seerr,
    private val linker: WatchlistPlexLinker = ServiceLocator.watchlistLinker,
) : ViewModel() {
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val resumeGate = ResumeRefreshGate()

    /** The title in the spotlight (#244), keyed by entry id; only on-server entries have details to fetch. */
    val spotlight = SpotlightController(viewModelScope)

    /** Plays an on-server title ([onPlay] gets the id), or says there's nothing to play. */
    fun play(
        item: BaseItemDto,
        onPlay: (String) -> Unit,
    ) {
        viewModelScope.launch {
            spotlight.playTarget(item)?.let(onPlay) ?: _messages.tryEmit("Nothing to play yet.")
        }
    }

    private fun syncSpotlight(view: WatchlistView) {
        val byId = view.entries.associateBy { it.id }
        spotlight.sync(view.entries.map { it.id }) { byId[it]?.itemId }
    }

    init {
        load()
        // Linked, switched (the app-wide Switch prompt) or disconnected, here or in Settings: re-read the Plex line.
        viewModelScope.launch { linker.changes.collect { load() } }
    }

    fun onScreenResumed() {
        if (resumeGate.onResume()) load()
    }

    fun load() {
        viewModelScope.launch {
            val view =
                runCatchingCancellable { service.view() }.getOrElse { e ->
                    // A failure is not absence: keep what's shown, or say it couldn't load.
                    _state.update { it.copy(isLoading = false, error = listErrorMessage(e)) }
                    return@launch
                }
            if (view == null) {
                _state.update { WatchlistUiState(isLoading = false, gone = true) }
                return@launch
            }
            _state.update { it.copy(isLoading = false, error = null, view = view, gone = false) }
            syncSpotlight(view)
            val ids = view.entries.mapNotNull { it.itemId }
            val items = runCatchingCancellable { service.libraryItems(ids) }.getOrNull()
            if (items != null) _state.update { it.copy(items = items) }
            _state.update { it.copy(badges = badges(view.entries)) }
        }
    }

    /** Requested or not, from the person's own Seerr session; at most four at once. */
    private suspend fun badges(entries: List<WatchlistEntry>): Map<String, SeerrBadge> {
        val client = seerr.client ?: return emptyMap()
        if (!seerr.isActive) return emptyMap()
        val userId = seerr.user?.id
        val gate = Semaphore(4)
        return entries
            .filter { it.itemId == null }
            .mapNotNull { e -> e.tmdbId?.toIntOrNull()?.let { e to it } }
            .map { (entry, tmdb) ->
                viewModelScope.async {
                    gate.withPermit {
                        runCatchingCancellable {
                            val kind = if (entry.isSeries) SeerrMediaKind.TV else SeerrMediaKind.MOVIE
                            entry.id to SeerrBadge.of(client.details(kind, tmdb).mediaInfo, userId)
                        }.getOrNull()
                    }
                }
            }.awaitAll()
            .filterNotNull()
            .toMap()
    }

    fun remove(entry: WatchlistEntry) {
        val before = _state.value.view ?: return
        _state.update { it.copy(view = before.copy(entries = before.entries.filterNot { e -> e.id == entry.id })) }
        _state.value.view?.let(::syncSpotlight)
        viewModelScope.launch {
            runCatchingCancellable { service.remove(entry.id) }.onFailure { e ->
                _state.update { s ->
                    s.copy(view = s.view?.let { v -> v.copy(entries = Watchlist.afterFailedRemove(v.entries, entry, before.entries)) })
                }
                _messages.tryEmit(listErrorMessage(e))
            }
        }
    }

    /** "Not now": hidden at once, for good on this account; back if the server didn't take it. */
    fun dismissCard() {
        val before = _state.value.view ?: return
        _state.update { it.copy(view = before.copy(cardDismissed = true)) }
        viewModelScope.launch {
            runCatchingCancellable { service.dismissCard() }.onFailure { e ->
                _state.update { s -> s.copy(view = s.view?.copy(cardDismissed = false)) }
                _messages.tryEmit(listErrorMessage(e))
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatchingCancellable { linker.disconnect() }
                .onFailure { _messages.tryEmit(listErrorMessage(it)) }
        }
    }

    /** A random on-server title, for Shuffle. */
    fun shuffleTarget(): String? =
        _state.value.view
            ?.entries
            ?.mapNotNull { it.itemId }
            ?.randomOrNull()
}
