package dev.bitstorm.sashimi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.home.ContinueWatchingHides
import dev.bitstorm.sashimi.core.home.ContinueWatchingSource
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.home.SideMenu
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.ResumeRefreshGate
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class HomeUiState(
    val isLoading: Boolean = false,
    /**
     * Set when the load genuinely failed, so the UI can distinguish that from a
     * genuinely empty account. Every call used to degrade to emptyList(), which
     * rendered a full library as "Start watching something to see it here."
     * whenever the server was down -- an outage presented as an empty account,
     * with no error, no retry and no clue what to do.
     */
    val error: String? = null,
    val continueWatching: List<BaseItemDto> = emptyList(),
    val continueWatchingLibraryNames: Map<String, String> = emptyMap(),
    val libraries: List<dev.bitstorm.sashimi.core.model.JellyfinLibrary> = emptyList(),
)

/** What the Home snackbar says after a Remove from Continue Watching. */
sealed interface RemovalNotice {
    data class Removed(
        val receipt: ContinueWatchingHides.Receipt,
    ) : RemovalNotice

    data object Failed : RemovalNotice

    data object UndoFailed : RemovalNotice
}

/** A subscribed list's Home row: the list as this viewer sees it, and how many are new. */
data class ListRowData(
    val list: dev.bitstorm.sashimi.core.lists.SharedList,
    val newIds: Set<String>,
)

/** Result of a Recently Added row load: deduped items + per-series unplayed counts. */
data class RecentlyAddedData(
    val items: List<BaseItemDto> = emptyList(),
    val badgeCounts: Map<String, Int> = emptyMap(),
)

/**
 * Home tab state. Port of Shared/ViewModels/HomeViewModel.swift (continue-
 * watching merge + library names) plus the per-row Recently Added loading that
 * MobileRecentlyAddedRow did in its own `.task`.
 */
class HomeViewModel(
    private val client: JellyfinClient,
    val homeRowSettings: HomeRowSettings,
    private val hides: ContinueWatchingHides,
    /** Outlives this ViewModel: a removal the user committed to must finish. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Snackbars for Remove from Continue Watching, one per removal. */
    private val _removals = MutableSharedFlow<RemovalNotice>(extraBufferCapacity = 4)
    val removals: SharedFlow<RemovalNotice> = _removals.asSharedFlow()

    /**
     * Cards removed but not yet confirmed by a reload. A load that started
     * before the removal finished would otherwise paint the card back.
     */
    private val pendingRemovals = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Recently Added results cached per libraryId. Rows read from here, so a row
     * scrolled out of and back into the LazyColumn reuses the cached data instead
     * of refetching (the source of the visible loading churn). A missing key means
     * "still loading" — the row shows placeholder cards until the value lands.
     */
    private val _recentlyAdded = MutableStateFlow<Map<String, RecentlyAddedData>>(emptyMap())
    val recentlyAdded: StateFlow<Map<String, RecentlyAddedData>> = _recentlyAdded.asStateFlow()

    /**
     * In-flight row loads, keyed by libraryId, so each row launches once.
     *
     * A Set could only record THAT a row was loading, not which coroutine, so a
     * refresh could not cancel the old one -- and the old job's removal deleted
     * the entry the NEW job had registered, defeating the in-flight guard for
     * every subsequent call.
     */
    private val loadingRows = mutableMapOf<String, Job>()

    /** Invalidates row loads started before the current refresh. */
    private var rowGeneration = 0

    val rows = homeRowSettings.rows

    /**
     * Subscribed lists by playlist id, loaded after the other rows so Home
     * never waits on them. A missing key: still loading (or the list is gone).
     */
    private val _listRows = MutableStateFlow<Map<String, ListRowData>>(emptyMap())
    val listRows: StateFlow<Map<String, ListRowData>> = _listRows.asStateFlow()

    private var listsJob: Job? = null

    init {
        // Reload when connectivity returns: content that loaded (or failed)
        // while offline is stale, and the offline->online composable swap
        // alone doesn't refetch reliably.
        viewModelScope.launch {
            var wasOnline = dev.bitstorm.sashimi.di.ServiceLocator.networkMonitor.isOnline.value
            dev.bitstorm.sashimi.di.ServiceLocator.networkMonitor.isOnline.collect { online ->
                if (online && !wasOnline) loadContent()
                wasOnline = online
            }
        }
    }

    fun loadContent() {
        _state.update { it.copy(isLoading = it.continueWatching.isEmpty()) }
        viewModelScope.launch { load() }
    }

    private val resumeGate = ResumeRefreshGate()

    /** Every RESUME of Home but the first reloads it. */
    fun onScreenResumed() {
        if (resumeGate.onResume()) loadContent()
    }

    /** Suspending reload for pull-to-refresh (the caller drives the spinner). */
    suspend fun refresh() = load(refreshRows = true)

    private suspend fun load(refreshRows: Boolean = false) {
        runCatching {
            // Each row is still individually tolerant -- one failing row should
            // not blank the others -- but the RESULTS are kept so a total
            // failure can be told apart from a genuinely empty account.
            val librariesDeferred = viewModelScope.async { runCatching { client.getUserViews() } }
            val continueWatchingDeferred = viewModelScope.async { ContinueWatchingSource.load(client, hides) }
            val librariesResult = librariesDeferred.await()

            // Libraries are the load-bearing call: a user with an account always
            // has at least one, so its failure means the server did not answer.
            if (librariesResult.isFailure) {
                continueWatchingDeferred.cancel()
                _state.update {
                    it.copy(isLoading = false, error = "Couldn't reach your server. Check that it's running, then try again.")
                }
                return@runCatching
            }

            val libs = librariesResult.getOrDefault(emptyList())
            val removedNow = pendingRemovals.value
            val cw = continueWatchingDeferred.await().filterNot { it.id in removedNow }
            val mediaLibraries = libs.filter { SideMenu.isMediaLibrary(it.collectionType) }
            homeRowSettings.updateLibraries(mediaLibraries)
            _state.update {
                it.copy(isLoading = false, error = null, continueWatching = cw, libraries = mediaLibraries)
            }
            if (refreshRows) {
                // Cancel, don't just forget. RecentlyAddedLoader issues a
                // getLatestMedia plus up to 20 sequential getItem calls for a TV
                // library, so these live for seconds: clearing the set left the
                // old load running, and if it finished second its stale result
                // overwrote the fresh one and the row showed pre-refresh content
                // until you navigated away and back.
                rowGeneration += 1
                loadingRows.values.forEach { it.cancel() }
                loadingRows.clear()
                _recentlyAdded.value = emptyMap()
            }
            prefetchRecentlyAdded(mediaLibraries)
            loadListRows()
            loadContinueWatchingLibraryNames(cw)
        }.onFailure {
            _state.update {
                it.copy(isLoading = false, error = "Something went wrong loading your library.")
            }
        }
    }

    /**
     * Kick off every enabled library row's Recently Added load up front (parallel),
     * so the data is usually ready before the row scrolls into view — replacing the
     * old per-row lazy fetch that spun a spinner each time a row appeared.
     */
    private fun prefetchRecentlyAdded(libraries: List<JellyfinLibrary>) {
        val byId = libraries.associateBy { it.id }
        for (row in homeRowSettings.rows.value) {
            if (row.kind != HomeRowConfig.Kind.LIBRARY || !row.isEnabled) continue
            val libraryId = row.libraryId ?: continue
            ensureRecentlyAdded(libraryId, row.libraryName ?: "", byId[libraryId]?.collectionType)
        }
    }

    /**
     * Load a row's Recently Added once and cache it by libraryId. Idempotent: a row
     * already cached or in flight is skipped, so scrolling it back into view never
     * refetches. Safe to call from row composition as a fallback for rows not
     * covered by the up-front prefetch.
     */
    fun ensureRecentlyAdded(
        libraryId: String,
        libraryName: String,
        collectionType: String?,
    ) {
        if (_recentlyAdded.value.containsKey(libraryId)) return
        if (loadingRows.containsKey(libraryId)) return
        val generation = rowGeneration
        loadingRows[libraryId] =
            viewModelScope.launch {
                val result =
                    runCatchingCancellable {
                        RecentlyAddedLoader.load(client, libraryId, libraryName, collectionType)
                    }.getOrDefault(RecentlyAddedData())
                // A refresh that started after this load owns the row now.
                if (generation != rowGeneration) return@launch
                _recentlyAdded.update { it + (libraryId to result) }
                // Only clear the entry if it is still OURS -- removing blindly
                // deleted a newer job's registration.
                if (loadingRows[libraryId] === coroutineContext[Job]) loadingRows.remove(libraryId)
            }
    }

    /**
     * Subscribed lists become Home rows. Each list loads on its own, in
     * parallel; a list that answers 404 (deleted, or made private) loses its
     * row and is pruned from the subscriptions on their next save. Any other
     * failure keeps the row as it was, so a blip never unsubscribes anyone.
     */
    private fun loadListRows() {
        listsJob?.cancel()
        listsJob =
            viewModelScope.launch {
                val service = ServiceLocator.sharedLists
                val subs = runCatchingCancellable { service.subscriptions() }.getOrNull() ?: return@launch
                // Every row's creator in one plugin call, not one per list.
                val owners = service.owners(subs.map { it.id })
                val loaded =
                    subs
                        .map { sub -> sub to async { runCatchingCancellable { service.list(sub.id, owners) } } }
                        .map { (sub, result) -> Triple(sub, result.await().getOrNull(), result.await().isSuccess) }
                val titles = homeRowSettings.rows.value.associate { it.listId to it.listTitle }
                homeRowSettings.updateListRows(
                    loaded
                        .filter { (sub, list, ok) -> list != null || !ok && sub.id !in service.missing }
                        .map { (sub, list, _) -> sub.id to (list?.rowTitle ?: titles[sub.id] ?: "List") },
                )
                _listRows.value =
                    loaded
                        .mapNotNull { (sub, list, _) ->
                            list ?: return@mapNotNull null
                            val seen = sub.seen.toSet()
                            sub.id to ListRowData(list, list.displayIds.filterNot { it in seen }.toSet())
                        }.toMap()
            }
    }

    /**
     * Remove from Continue Watching: the card goes at once, the server (or the
     * local hide store) is updated, then the row reloads. A failure puts the
     * card back on that reload and says so.
     */
    fun removeFromContinueWatching(item: BaseItemDto) {
        pendingRemovals.update { it + item.id }
        _state.update { s -> s.copy(continueWatching = s.continueWatching.filterNot { it.id == item.id }) }
        appScope.launch {
            val result = hides.remove(client, item)
            if (result is ContinueWatchingHides.Result.Failed) pendingRemovals.update { it - item.id }
            _removals.tryEmit(
                when (result) {
                    is ContinueWatchingHides.Result.Removed -> RemovalNotice.Removed(result.receipt)
                    is ContinueWatchingHides.Result.Failed -> RemovalNotice.Failed
                },
            )
            load()
            pendingRemovals.update { it - item.id }
        }
    }

    fun undoRemoval(receipt: ContinueWatchingHides.Receipt) {
        appScope.launch {
            val restored = runCatchingCancellable { hides.undo(client, receipt) }
            if (restored.isFailure) _removals.tryEmit(RemovalNotice.UndoFailed)
            load()
        }
    }

    private suspend fun loadContinueWatchingLibraryNames(items: List<BaseItemDto>) {
        val seriesIds =
            items.mapNotNull { if (it.type == ItemType.EPISODE) it.seriesId else it.id }.toSet()
        val names = mutableMapOf<String, String>()
        for (seriesId in seriesIds) {
            runCatching {
                val ancestors = client.getItemAncestors(seriesId)
                val library = ancestors.firstOrNull { it.type == ItemType.COLLECTION_FOLDER }
                if (library != null) {
                    for (item in items) {
                        if (item.seriesId == seriesId || item.id == seriesId) {
                            names[item.id] = library.name
                        }
                    }
                }
            }
        }
        _state.update { it.copy(continueWatchingLibraryNames = names) }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                ServiceLocator.client,
                ServiceLocator.homeRowSettings,
                ServiceLocator.continueWatchingHides,
                ServiceLocator.appScope,
            ) as T
    }
}
