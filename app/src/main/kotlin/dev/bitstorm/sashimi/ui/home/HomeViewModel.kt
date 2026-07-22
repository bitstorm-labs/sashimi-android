package dev.bitstorm.sashimi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime

data class HomeUiState(
    val isLoading: Boolean = false,
    val continueWatching: List<BaseItemDto> = emptyList(),
    val continueWatchingLibraryNames: Map<String, String> = emptyMap(),
    val libraries: List<dev.bitstorm.sashimi.core.model.JellyfinLibrary> = emptyList(),
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
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * Recently Added results cached per libraryId. Rows read from here, so a row
     * scrolled out of and back into the LazyColumn reuses the cached data instead
     * of refetching (the source of the visible loading churn). A missing key means
     * "still loading" — the row shows placeholder cards until the value lands.
     */
    private val _recentlyAdded = MutableStateFlow<Map<String, RecentlyAddedData>>(emptyMap())
    val recentlyAdded: StateFlow<Map<String, RecentlyAddedData>> = _recentlyAdded.asStateFlow()

    /** libraryIds whose Recently Added fetch is in flight, so we launch each once. */
    private val loadingRows = mutableSetOf<String>()

    val rows = homeRowSettings.rows

    fun loadContent() {
        _state.update { it.copy(isLoading = it.continueWatching.isEmpty()) }
        viewModelScope.launch { load() }
    }

    /** Suspending reload for pull-to-refresh (the caller drives the spinner). */
    suspend fun refresh() = load(refreshRows = true)

    private suspend fun load(refreshRows: Boolean = false) {
        runCatching {
            val resumeDeferred = viewModelScope.async { runCatching { client.getResumeItems() }.getOrDefault(emptyList()) }
            val nextUpDeferred = viewModelScope.async { runCatching { client.getNextUp() }.getOrDefault(emptyList()) }
            val librariesDeferred = viewModelScope.async { runCatching { client.getUserViews() }.getOrDefault(emptyList()) }
            val resume = resumeDeferred.await()
            val nextUp = nextUpDeferred.await()
            val libs = librariesDeferred.await()

            val cw = mergeContinueWatching(resume, nextUp)
            val mediaLibraries = libs.filter { isMediaLibrary(it.collectionType) }
            homeRowSettings.updateLibraries(mediaLibraries)
            _state.update {
                it.copy(isLoading = false, continueWatching = cw, libraries = mediaLibraries)
            }
            if (refreshRows) {
                loadingRows.clear()
                _recentlyAdded.value = emptyMap()
            }
            prefetchRecentlyAdded(mediaLibraries)
            loadContinueWatchingLibraryNames(cw)
        }.onFailure {
            _state.update { it.copy(isLoading = false) }
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
        if (!loadingRows.add(libraryId)) return
        viewModelScope.launch {
            val result =
                runCatching {
                    RecentlyAddedLoader.load(client, libraryId, libraryName, collectionType)
                }.getOrDefault(RecentlyAddedData())
            _recentlyAdded.update { it + (libraryId to result) }
            loadingRows.remove(libraryId)
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

    /**
     * Merge Resume + Next Up into the Continue Watching list, most-recent first,
     * one entry per series, capped at 20. Port of HomeViewModel.mergeAndSort.
     */
    private fun mergeContinueWatching(
        resume: List<BaseItemDto>,
        nextUp: List<BaseItemDto>,
    ): List<BaseItemDto> {
        val now = Instant.now().toEpochMilli()
        val resumeDates = resume.map { parseDate(it.userData?.lastPlayedDate) ?: now }
        val nextUpDates = nextUp.indices.map { now - it * 1000L }

        val merged = mutableListOf<BaseItemDto>()
        val seenSeries = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        var r = 0
        var n = 0
        while (r < resume.size || n < nextUp.size) {
            val useResume =
                when {
                    r >= resume.size -> false
                    n >= nextUp.size -> true
                    else -> resumeDates[r] >= nextUpDates[n]
                }
            val item = if (useResume) resume[r++] else nextUp[n++]
            if (!seenIds.add(item.id)) continue
            val seriesId = item.seriesId
            if (seriesId != null && !seenSeries.add(seriesId)) continue
            merged.add(item)
            if (merged.size >= 20) break
        }
        return merged
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(raw).toEpochMilli() }
            .getOrNull()
    }

    private fun isMediaLibrary(collectionType: String?): Boolean {
        val type = collectionType?.lowercase() ?: return true
        return type in setOf("movies", "tvshows", "music", "mixed", "homevideos")
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(ServiceLocator.client, ServiceLocator.homeRowSettings) as T
    }
}
