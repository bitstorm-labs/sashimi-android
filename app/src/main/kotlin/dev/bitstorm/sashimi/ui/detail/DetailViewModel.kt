package dev.bitstorm.sashimi.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.home.NextUpSelector
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.MediaSourceInfo
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val item: BaseItemDto? = null,
    val isLoading: Boolean = true,
    val isWatched: Boolean = false,
    val hasProgress: Boolean = false,
    val seasons: List<BaseItemDto> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<BaseItemDto> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val nextEpisode: BaseItemDto? = null,
    val mediaInfo: MediaSourceInfo? = null,
    val seriesCommunityRating: Double? = null,
    val seriesCriticRating: Int? = null,
    val deleted: Boolean = false,
    val error: String? = null,
) {
    val isSeries get() = item?.type == ItemType.SERIES
    val isEpisode get() = item?.type == ItemType.EPISODE
    val isMovie get() = item?.type == ItemType.MOVIE

    /** The episode this page treats as "current" (highlighted in the list). */
    val currentEpisodeId: String?
        get() = if (isEpisode) item?.id else nextEpisode?.id
}

/**
 * Detail state machine. Port of PhoneDetailView / MobileDetailView data loading.
 * On entry it re-fetches the full item (the cast lesson: list/search endpoints
 * omit People, so cast never showed without this refresh — commented in Swift).
 */
class DetailViewModel(
    private val client: JellyfinClient,
    private val itemId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val fresh = runCatching { client.getItem(itemId) }.getOrNull()
            if (fresh == null) {
                _state.update { it.copy(isLoading = false, error = "Could not load item.") }
                return@launch
            }
            _state.update {
                it.copy(
                    item = fresh,
                    isLoading = false,
                    isWatched = fresh.userData?.played == true,
                    hasProgress = fresh.progressPercent > 0,
                )
            }
            loadContent(fresh)
        }
    }

    private suspend fun loadContent(item: BaseItemDto) {
        when (item.type) {
            ItemType.SERIES -> loadSeriesContent(item)
            ItemType.EPISODE -> loadEpisodeContent(item)
            else -> {}
        }
        // Media badges for everything but series.
        if (item.type != ItemType.SERIES) {
            val info = runCatching { client.getPlaybackInfo(item.id) }.getOrNull()?.mediaSources?.firstOrNull()
            _state.update { it.copy(mediaInfo = info) }
        }
    }

    private suspend fun loadSeriesContent(item: BaseItemDto) {
        val seasons = runCatching { client.getSeasons(item.id) }.getOrDefault(emptyList())
        _state.update { it.copy(seasons = seasons) }

        val next = findNextEpisode(item.id, seasons)
        _state.update { it.copy(nextEpisode = next) }

        val season =
            next?.seasonId?.let { sid -> seasons.firstOrNull { it.id == sid } } ?: seasons.firstOrNull()
        if (season != null) {
            _state.update { it.copy(selectedSeasonId = season.id) }
            loadEpisodesForSeason(item.id, season.id)
        }
    }

    private suspend fun loadEpisodeContent(item: BaseItemDto) {
        val seriesId = item.seriesId ?: return
        val series = runCatching { client.getItem(seriesId) }.getOrNull()
        val seasons = runCatching { client.getSeasons(seriesId) }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                seasons = seasons,
                seriesCommunityRating = series?.communityRating,
                seriesCriticRating = series?.criticRating,
                selectedSeasonId = item.seasonId,
            )
        }
        item.seasonId?.let { loadEpisodesForSeason(seriesId, it) }
    }

    fun selectSeason(seasonId: String) {
        val item = _state.value.item ?: return
        val seriesId = if (item.type == ItemType.SERIES) item.id else item.seriesId ?: return
        _state.update { it.copy(selectedSeasonId = seasonId) }
        viewModelScope.launch { loadEpisodesForSeason(seriesId, seasonId) }
    }

    private suspend fun loadEpisodesForSeason(
        seriesId: String,
        seasonId: String,
    ) {
        _state.update { it.copy(isLoadingEpisodes = true) }
        val episodes =
            runCatching { client.getEpisodes(seriesId = seriesId, seasonId = seasonId) }.getOrDefault(emptyList())
        _state.update { it.copy(episodes = episodes, isLoadingEpisodes = false) }
    }

    /** Next-up: server Next Up for this series, else first unwatched across seasons. */
    private suspend fun findNextEpisode(
        seriesId: String,
        seasons: List<BaseItemDto>,
    ): BaseItemDto? {
        val nextUp = runCatching { client.getNextUp(limit = 50) }.getOrDefault(emptyList())
        NextUpSelector.fromNextUp(nextUp, seriesId)?.let { return it }
        for (season in seasons) {
            val eps = runCatching { client.getEpisodes(seriesId, season.id) }.getOrDefault(emptyList())
            NextUpSelector.firstUnwatched(eps)?.let { return it }
        }
        return null
    }

    /** Optimistic watched toggle. Port of toggleWatched. */
    fun toggleWatched() {
        val item = _state.value.item ?: return
        val newState = !_state.value.isWatched
        _state.update { it.copy(isWatched = newState, hasProgress = if (newState) false else it.hasProgress) }
        viewModelScope.launch {
            val ok =
                runCatching {
                    if (newState) client.markPlayed(item.id) else client.markUnplayed(item.id)
                }.isSuccess
            if (!ok) _state.update { it.copy(isWatched = !newState) }
        }
    }

    /** Favorite toggle (from the overflow menu). */
    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val makeFavorite = item.userData?.isFavorite != true
        viewModelScope.launch {
            runCatching {
                if (makeFavorite) client.markFavorite(item.id) else client.removeFavorite(item.id)
            }
            runCatching { client.getItem(item.id) }.getOrNull()?.let { fresh ->
                _state.update { it.copy(item = fresh) }
            }
        }
    }

    /**
     * The item id to hand the player when Play/Resume is tapped: for a series the
     * resolved next-up episode, otherwise this item.
     */
    fun playTargetId(): String? = if (_state.value.isSeries) _state.value.nextEpisode?.id else _state.value.item?.id

    /** First local trailer item id for the Trailer button, or null. */
    suspend fun firstLocalTrailerId(): String? = runCatching { client.getLocalTrailers(itemId).firstOrNull()?.id }.getOrNull()

    /** Re-fetch after returning from the player so progress/watched state refreshes. */
    fun reload() = load()

    /** Shuffle: a random episode of this series (navigates to its detail). */
    suspend fun randomEpisode(): BaseItemDto? {
        val item = _state.value.item ?: return null
        val seriesId = if (item.type == ItemType.SERIES) item.id else item.seriesId ?: return null
        return runCatching { client.getRandomItem(seriesId, listOf(ItemType.EPISODE)) }.getOrNull()
    }

    fun refreshMetadata() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val ok = runCatching { client.refreshMetadata(item.id) }.isSuccess
            if (!ok) {
                _state.update { it.copy(error = "Failed to refresh metadata.") }
                return@launch
            }
            kotlinx.coroutines.delay(2000)
            runCatching { client.getItem(item.id) }.getOrNull()?.let { fresh ->
                _state.update { it.copy(item = fresh) }
            }
        }
    }

    fun deleteItem() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val ok = runCatching { client.deleteItem(item.id) }.isSuccess
            if (ok) {
                _state.update { it.copy(deleted = true) }
            } else {
                _state.update { it.copy(error = "Failed to delete item.") }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    class Factory(
        private val itemId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DetailViewModel(ServiceLocator.client, itemId) as T
    }
}
