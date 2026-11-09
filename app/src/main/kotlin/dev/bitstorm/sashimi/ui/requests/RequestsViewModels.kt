package dev.bitstorm.sashimi.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.seerr.SeerrDiscover
import dev.bitstorm.sashimi.core.seerr.SeerrDiscoverSlider
import dev.bitstorm.sashimi.core.seerr.SeerrHomeRequests
import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrRequestAction
import dev.bitstorm.sashimi.core.seerr.SeerrRequestFilter
import dev.bitstorm.sashimi.core.seerr.SeerrRequestList
import dev.bitstorm.sashimi.core.seerr.SeerrRequestRow
import dev.bitstorm.sashimi.core.seerr.SeerrRequestScope
import dev.bitstorm.sashimi.core.seerr.SeerrSearch
import dev.bitstorm.sashimi.core.seerr.SeerrSearchItem
import dev.bitstorm.sashimi.core.seerr.SeerrSearchMerge
import dev.bitstorm.sashimi.core.seerr.SeerrSeasonPlan
import dev.bitstorm.sashimi.core.seerr.SeerrSession
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import dev.bitstorm.sashimi.core.seerr.SeerrTitle
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The active Jellyfin server's copy of a Seerr title, by TMDB id among a name search. */
internal suspend fun findInLibrary(
    tmdbId: Int,
    kind: SeerrMediaKind,
    title: String,
): BaseItemDto? {
    val items = runCatchingCancellable { ServiceLocator.client.search(title, limit = 20) }.getOrNull() ?: return null
    return SeerrSearchMerge.libraryMatch(items, tmdbId, kind)
}

/**
 * The Requests page: Discover and the request list, plus the page's own
 * search (everything Seerr finds, what's on the server included).
 */
class RequestsViewModel(
    private val session: SeerrSession = ServiceLocator.seerr,
) : ViewModel() {
    val discover = SeerrDiscover(session)
    val list = SeerrRequestList(session)
    val search = SeerrSearch(session, includesAvailable = true)

    private val _filter = MutableStateFlow(SeerrRequestFilter.ALL)
    val filter: StateFlow<SeerrRequestFilter> = _filter.asStateFlow()
    private val _scope = MutableStateFlow(SeerrRequestScope.EVERYONE)
    val scope: StateFlow<SeerrRequestScope> = _scope.asStateFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var loaded = false

    init {
        // A request sent, approved or cancelled anywhere reloads what's open.
        viewModelScope.launch {
            session.requestsRevision.drop(1).collect { reloadAll() }
        }
        // Signing in or out changes what the page can show.
        viewModelScope.launch {
            session.state
                .map { it.user?.id }
                .distinctUntilChanged()
                .drop(1)
                .collect { reloadAll() }
        }
    }

    fun loadOnce() {
        if (loaded) return
        loaded = true
        reloadAll()
    }

    fun reloadAll() {
        viewModelScope.launch { discover.load() }
        reloadList()
    }

    fun setFilter(filter: SeerrRequestFilter) {
        _filter.value = filter
        list.filter = filter
        reloadList()
    }

    fun setScope(scope: SeerrRequestScope) {
        _scope.value = scope
        list.scope = scope
        reloadList()
    }

    private fun reloadList() {
        listJob?.cancel()
        listJob = viewModelScope.launch { list.reload() }
    }

    fun loadMore(row: SeerrRequestRow) {
        viewModelScope.launch { list.loadMoreIfNeeded(row) }
    }

    fun loadMore(
        slider: SeerrDiscoverSlider,
        item: SeerrSearchItem,
    ) {
        viewModelScope.launch { discover.loadMoreIfNeeded(slider, item) }
    }

    fun perform(
        action: SeerrRequestAction,
        row: SeerrRequestRow,
    ) {
        viewModelScope.launch { list.perform(action, row) }
    }

    fun clearListError() = list.clearError()

    /** Debounced: typing a title searches once the person pauses. */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            search.clear()
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(350)
                search.search(query, emptyList())
            }
    }
}

/** One Seerr title's request page. */
class SeerrTitleViewModel(
    target: SeerrTarget,
    session: SeerrSession = ServiceLocator.seerr,
) : ViewModel() {
    val model = SeerrTitle(target, session, ::findInLibrary)

    /** Whether this server has a Watchlist, so the page offers Add to List. Asked once. */
    private val _watchlistAvailable = MutableStateFlow(false)
    val watchlistAvailable: StateFlow<Boolean> = _watchlistAvailable.asStateFlow()

    init {
        viewModelScope.launch { model.load() }
        viewModelScope.launch { _watchlistAvailable.value = ServiceLocator.watchlist.available() }
    }

    fun requestMovie() {
        viewModelScope.launch { model.requestMovie() }
    }

    fun requestSeasons(plan: SeerrSeasonPlan) {
        viewModelScope.launch { model.requestSeasons(plan) }
    }

    fun clearError() = model.clearError()
}

/** Home's requests row. Reloads with every Home load and after any request change. */
class HomeRequestsViewModel(
    private val session: SeerrSession = ServiceLocator.seerr,
) : ViewModel() {
    val model = SeerrHomeRequests(session)

    init {
        viewModelScope.launch {
            session.state
                .map { it.user?.id }
                .distinctUntilChanged()
                .collect { load() }
        }
        viewModelScope.launch { session.requestsRevision.drop(1).collect { load() } }
    }

    fun load() {
        viewModelScope.launch { model.load() }
    }
}
