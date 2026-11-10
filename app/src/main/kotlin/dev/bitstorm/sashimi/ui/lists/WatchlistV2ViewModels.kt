package dev.bitstorm.sashimi.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.lists.Watchlist
import dev.bitstorm.sashimi.core.lists.WatchlistSearchHit
import dev.bitstorm.sashimi.core.lists.WatchlistSearchResult
import dev.bitstorm.sashimi.core.lists.WatchlistService
import dev.bitstorm.sashimi.core.lists.WatchlistTitleDetails
import dev.bitstorm.sashimi.core.lists.WatchlistTitleRef
import dev.bitstorm.sashimi.core.lists.WatchlistV2
import dev.bitstorm.sashimi.core.seerr.SeerrSession
import dev.bitstorm.sashimi.core.seerr.SeerrTitle
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.requests.findInLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// MARK: - ＋ Add

data class WatchlistAddUiState(
    val text: String = "",
    val isSearching: Boolean = false,
    /** The last answer for the text in the box; null before the first search. */
    val result: WatchlistSearchResult? = null,
    val error: String? = null,
    /** The page's own adds since the search ([WatchlistV2.key] → entry id), on top of the server's onWatchlist. */
    val added: Map<String, String> = emptyMap(),
    /** Adds on their way to the server. */
    val adding: Set<String> = emptySet(),
    /** The plugin answered 404 after all: no search on this server. */
    val unavailable: Boolean = false,
) {
    fun isAdded(hit: WatchlistSearchHit): Boolean = hit.onWatchlist || WatchlistV2.key(hit) in added
}

/** ＋ Add's search (watchlist v2 §2): debounced, the stale reply dropped, ＋ adds straight from the list. */
class WatchlistAddViewModel(
    private val service: WatchlistService = ServiceLocator.watchlist,
) : ViewModel() {
    private val _state = MutableStateFlow(WatchlistAddUiState())
    val state: StateFlow<WatchlistAddUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var searchJob: Job? = null

    fun onTextChange(text: String) {
        _state.update { it.copy(text = text) }
        searchJob?.cancel()
        if (!WatchlistV2.searchable(text)) {
            _state.update { it.copy(isSearching = false, result = null, error = null) }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MILLIS)
                search(text)
            }
    }

    /** Back from a title's page, where it may have been added or removed: ask again, the server's word replaces ours. */
    fun onScreenResumed() {
        if (_state.value.result == null || !WatchlistV2.searchable(_state.value.text)) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(_state.value.text, fresh = true) }
    }

    fun retry() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(_state.value.text) }
    }

    private suspend fun search(
        text: String,
        fresh: Boolean = false,
    ) {
        _state.update { it.copy(isSearching = true, error = null) }
        runCatchingCancellable { service.search(text) }
            .onSuccess { result ->
                when {
                    result == null -> _state.update { it.copy(isSearching = false, unavailable = true) }
                    WatchlistV2.isCurrent(result, _state.value.text) ->
                        _state.update { it.copy(isSearching = false, result = result, added = if (fresh) emptyMap() else it.added) }
                    // A reply for older text: the newer search is on its way.
                    else -> Unit
                }
            }.onFailure { e -> _state.update { it.copy(isSearching = false, error = listErrorMessage(e)) } }
    }

    fun add(hit: WatchlistSearchHit) {
        val key = WatchlistV2.key(hit)
        if (_state.value.isAdded(hit) || key in _state.value.adding) return
        _state.update { it.copy(adding = it.adding + key) }
        viewModelScope.launch {
            runCatchingCancellable { service.add(WatchlistV2.newEntry(hit)) }
                .onSuccess { reply -> _state.update { it.copy(added = it.added + (key to reply.entry.id)) } }
                .onFailure { _messages.tryEmit(listErrorMessage(it)) }
            _state.update { it.copy(adding = it.adding - key) }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 350L
    }
}

// MARK: - A title not on the server

sealed interface TitleDetailsState {
    data object Loading : TitleDetailsState

    data class Ready(
        val details: WatchlistTitleDetails,
    ) : TitleDetailsState

    /** No provider knows it, or the plugin is older: the page shows what it was given. */
    data object Unavailable : TitleDetailsState

    data class Failed(
        val message: String,
    ) : TitleDetailsState
}

data class WatchlistTitleUiState(
    val details: TitleDetailsState = TitleDetailsState.Loading,
    /** On the Watchlist, with its entry id for the toggle-off. Null id: not on it. */
    val entryId: String? = null,
    /** Whether the Watchlist state is known yet. */
    val watchlistKnown: Boolean = false,
    val saving: Boolean = false,
    /** The title is on the server after all: the page hands over to the library page. */
    val libraryItemId: String? = null,
)

/**
 * The title page for a title the server doesn't have (watchlist v2 §3): its
 * details from the server's metadata providers, the Watchlist toggle, and the
 * existing Seerr request flow when signed into Requests.
 */
class WatchlistTitleViewModel(
    val ref: WatchlistTitleRef,
    private val service: WatchlistService = ServiceLocator.watchlist,
    private val seerr: SeerrSession = ServiceLocator.seerr,
) : ViewModel() {
    private val _state = MutableStateFlow(WatchlistTitleUiState())
    val state: StateFlow<WatchlistTitleUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Request / Requested, from the person's own Seerr session; null when not signed in or no TMDB id. */
    private val _request = MutableStateFlow<SeerrTitle?>(null)
    val request: StateFlow<SeerrTitle?> = _request.asStateFlow()

    init {
        load()
        refreshRequest()
    }

    /** On open and on coming back (from signing into Requests, say): Request appears or goes with the sign-in. */
    fun refreshRequest() {
        val target = ref.seerrTarget()?.takeIf { seerr.isActive }
        if (target == null) {
            _request.value = null
            return
        }
        if (_request.value != null) return
        val model = SeerrTitle(target, seerr, ::findInLibrary)
        _request.value = model
        viewModelScope.launch { model.load() }
    }

    fun load() {
        _state.update { it.copy(details = TitleDetailsState.Loading) }
        viewModelScope.launch {
            runCatchingCancellable { service.details(ref) }
                .onSuccess { details ->
                    if (details == null) {
                        _state.update { it.copy(details = TitleDetailsState.Unavailable) }
                        loadWatchlistState()
                    } else {
                        _state.update {
                            it.copy(
                                details = TitleDetailsState.Ready(details),
                                entryId = details.watchlistEntryId.takeIf { details.onWatchlist },
                                watchlistKnown = true,
                                libraryItemId = details.itemId,
                            )
                        }
                    }
                }.onFailure { e ->
                    _state.update { it.copy(details = TitleDetailsState.Failed(listErrorMessage(e))) }
                    loadWatchlistState()
                }
        }
    }

    /** Without details, the Watchlist itself says whether the title is on it. */
    private suspend fun loadWatchlistState() {
        val view = runCatchingCancellable { service.view() }.getOrNull() ?: return
        val candidate = ref.newEntry()
        val entry = view.entries.firstOrNull { it.type == ref.type && Watchlist.matches(it, candidate) }
        _state.update { it.copy(entryId = entry?.id, watchlistKnown = true) }
    }

    /** Add to Watchlist / ✓ On Watchlist. Adding never requests the title. */
    fun toggleWatchlist() {
        val s = _state.value
        if (s.saving || !s.watchlistKnown) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val entryId = s.entryId
            runCatchingCancellable {
                if (entryId != null) {
                    service.remove(entryId)
                    null
                } else {
                    val body = (s.details as? TitleDetailsState.Ready)?.let { WatchlistV2.newEntry(it.details) } ?: ref.newEntry()
                    service.add(body).entry.id
                }
            }.onSuccess { id -> _state.update { it.copy(entryId = id) } }
                .onFailure { _messages.tryEmit(listErrorMessage(it)) }
            _state.update { it.copy(saving = false) }
        }
    }

    fun requestMovie() {
        val model = _request.value ?: return
        viewModelScope.launch { model.requestMovie() }
    }

    fun requestSeasons(plan: dev.bitstorm.sashimi.core.seerr.SeerrSeasonPlan) {
        val model = _request.value ?: return
        viewModelScope.launch { model.requestSeasons(plan) }
    }
}
