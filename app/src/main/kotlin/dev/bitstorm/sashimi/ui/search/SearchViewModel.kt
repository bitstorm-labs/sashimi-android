package dev.bitstorm.sashimi.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.search.RecentSearchStore
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val searchText: String = "",
    val results: List<BaseItemDto> = emptyList(),
    val isSearching: Boolean = false,
)

/**
 * Search state: 300ms-debounced query + a 1.5s-debounced history commit that
 * only records queries the user settled on that produced results. Port of
 * MobileSearchView. Recent searches come from the shared [RecentSearchStore].
 */
class SearchViewModel(
    private val client: JellyfinClient,
    private val recentSearchStore: RecentSearchStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val recentSearches = recentSearchStore.searches

    private var searchJob: Job? = null
    private var historyJob: Job? = null

    fun onSearchTextChange(text: String) {
        _state.update { it.copy(searchText = text) }

        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                performSearch(text)
            }

        // History: only after a 1.5s settle on a query that returned results.
        historyJob?.cancel()
        historyJob =
            viewModelScope.launch {
                delay(HISTORY_DEBOUNCE_MS)
                if (text.isNotEmpty() && _state.value.searchText == text && _state.value.results.isNotEmpty()) {
                    recentSearchStore.add(text)
                }
            }
    }

    /** Tapping a recent-search chip re-runs it immediately. */
    fun applyRecentSearch(query: String) = onSearchTextChange(query)

    fun clearRecentSearches() = recentSearchStore.clear()

    private suspend fun performSearch(query: String) {
        if (query.isEmpty()) {
            _state.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        val results = runCatching { client.search(query = query, limit = 50) }.getOrDefault(emptyList())
        // Ignore a stale response if the text moved on.
        if (_state.value.searchText == query) {
            _state.update { it.copy(results = results, isSearching = false) }
        } else {
            _state.update { it.copy(isSearching = false) }
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(ServiceLocator.client, ServiceLocator.recentSearchStore) as T
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val HISTORY_DEBOUNCE_MS = 1_500L
    }
}
