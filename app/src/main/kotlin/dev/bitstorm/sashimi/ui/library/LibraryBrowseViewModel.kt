package dev.bitstorm.sashimi.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.browse.LibraryFilter
import dev.bitstorm.sashimi.core.browse.LibrarySort
import dev.bitstorm.sashimi.core.browse.SortDirection
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryBrowseUiState(
    val isLoading: Boolean = true,
    val items: List<BaseItemDto> = emptyList(),
    val totalCount: Int = 0,
    val sort: LibrarySort = LibrarySort.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val searchText: String = "",
) {
    /** Client-side search over the eagerly-loaded items (Swift displayedItems). */
    val displayedItems: List<BaseItemDto>
        get() =
            if (searchText.isBlank()) {
                items
            } else {
                items.filter { it.name.contains(searchText, ignoreCase = true) }
            }
}

/**
 * Library browse state: sort/filter/search + background paging (100/page). Port
 * of MobileLibraryBrowseView. A generation counter invalidates an in-flight
 * page loop when sort/filter changes mid-fetch.
 */
class LibraryBrowseViewModel(
    private val client: JellyfinClient,
    private val libraryId: String,
    private val collectionType: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryBrowseUiState())
    val state: StateFlow<LibraryBrowseUiState> = _state.asStateFlow()

    private var loadGeneration = 0

    private val includeTypes: List<ItemType>?
        get() =
            when (collectionType) {
                "movies" -> listOf(ItemType.MOVIE)
                "tvshows" -> listOf(ItemType.SERIES)
                else -> null
            }

    init {
        reload()
    }

    fun setSort(sort: LibrarySort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        reload()
    }

    fun toggleDirection() {
        _state.update { it.copy(direction = it.direction.toggled()) }
        reload()
    }

    fun setFilter(filter: LibraryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        reload()
    }

    fun setSearchText(text: String) = _state.update { it.copy(searchText = text) }

    /** One random movie/episode for the Shuffle button (navigates to detail in M2). */
    suspend fun shuffleItem(): BaseItemDto? {
        val types = if (collectionType == "tvshows") listOf(ItemType.EPISODE) else listOf(ItemType.MOVIE)
        return runCatching { client.getRandomItem(parentId = libraryId, includeTypes = types) }.getOrNull()
    }

    fun refreshAfterAction() = reload()

    private fun reload() {
        loadGeneration += 1
        val generation = loadGeneration
        _state.update { it.copy(isLoading = true, items = emptyList(), totalCount = 0) }
        viewModelScope.launch { loadItems(generation) }
    }

    private suspend fun loadItems(generation: Int) {
        val s = _state.value
        runCatching {
            val first =
                client.getItems(
                    parentId = libraryId,
                    includeTypes = includeTypes,
                    sortBy = s.sort.wire,
                    sortOrder = s.direction.wire,
                    limit = PAGE_SIZE,
                    startIndex = 0,
                    isPlayed = s.filter.isPlayed,
                    isFavorite = s.filter.isFavorite,
                )
            if (generation != loadGeneration) return
            var accumulated = first.items
            _state.update { it.copy(items = accumulated, totalCount = first.totalRecordCount) }

            // Load remaining pages in the background so client-side search sees all.
            while (accumulated.size < first.totalRecordCount) {
                val more =
                    client.getItems(
                        parentId = libraryId,
                        includeTypes = includeTypes,
                        sortBy = s.sort.wire,
                        sortOrder = s.direction.wire,
                        limit = PAGE_SIZE,
                        startIndex = accumulated.size,
                        isPlayed = s.filter.isPlayed,
                        isFavorite = s.filter.isFavorite,
                    )
                if (generation != loadGeneration) return
                if (more.items.isEmpty()) break
                accumulated = accumulated + more.items
                _state.update { it.copy(items = accumulated) }
            }
        }
        if (generation == loadGeneration) {
            _state.update { it.copy(isLoading = false) }
        }
    }

    class Factory(
        private val libraryId: String,
        private val collectionType: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryBrowseViewModel(ServiceLocator.client, libraryId, collectionType) as T
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}
