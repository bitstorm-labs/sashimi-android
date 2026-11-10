package dev.bitstorm.sashimi.ui.lists

import dev.bitstorm.sashimi.core.lists.ListSpotlight
import dev.bitstorm.sashimi.core.lists.SpotlightDetails
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.shuffle.JellyfinTvShuffleSource
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The spotlight half of a list page's view model: which title is selected and
 * the full item behind it (overview, genres, seasons, the series' backdrop),
 * fetched once per title. Keys are the page's own (a list card's display id,
 * a Watchlist entry id); [select] takes the library id to fetch, null for a
 * title the server doesn't have.
 */
class SpotlightController(
    private val scope: CoroutineScope,
    private val details: SpotlightDetails = SpotlightDetails { ServiceLocator.client.getItem(it) },
) {
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Full items by library id, as they arrive. */
    private val _loaded = MutableStateFlow<Map<String, BaseItemDto>>(emptyMap())
    val loaded: StateFlow<Map<String, BaseItemDto>> = _loaded.asStateFlow()

    fun select(
        key: String,
        libraryId: String?,
    ) {
        _selected.value = key
        if (libraryId == null || libraryId in _loaded.value) return
        scope.launch {
            details.load(libraryId)?.let { item -> _loaded.update { it + (libraryId to item) } }
        }
    }

    /** After the list changed: keep the spotlight on its title while it's there, else the first. */
    fun sync(
        keys: List<String>,
        libraryIdOf: (String) -> String?,
    ) {
        val key = ListSpotlight.selected(keys, _selected.value)
        if (key == null) {
            _selected.value = null
        } else {
            select(key, libraryIdOf(key))
        }
    }

    /** The id Play starts for [item] (a show's next episode), null when there's nothing to play. */
    suspend fun playTarget(item: BaseItemDto): String? =
        runCatchingCancellable { ListSpotlight.playTarget(item, JellyfinTvShuffleSource(ServiceLocator.client)) }.getOrNull()
}
