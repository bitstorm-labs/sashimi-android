package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.seerr.SeerrTitleDetails
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What Add to List is adding: a library title, or a Seerr title (Watchlist only). */
sealed interface AddToListTarget {
    val title: String
    val key: String

    data class Library(
        val item: BaseItemDto,
    ) : AddToListTarget {
        override val title: String get() = item.name
        override val key: String get() = item.id
    }

    data class Seerr(
        val details: SeerrTitleDetails,
    ) : AddToListTarget {
        override val title: String get() = details.title
        override val key: String get() = "seerr-${details.kind.wire}-${details.tmdbId}"
    }
}

/** One of your lists in the picker. */
data class PickerRow(
    val list: SharedList,
    val alreadyOn: Boolean,
)

/** The Watchlist row, always first. Null in [AddToListUiState.watchlist] hides it. */
data class WatchlistRow(
    val alreadyOn: Boolean,
    val count: Int,
)

data class AddToListUiState(
    val isLoading: Boolean = true,
    val watchlist: WatchlistRow? = null,
    val rows: List<PickerRow> = emptyList(),
    /** False for a Seerr title: only the Watchlist can take it. */
    val showsLists: Boolean = true,
    /** A failure that leaves nothing to pick from. */
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * Builds what the Add to List picker shows for one title: the Watchlist row,
 * then your lists, marked where the title already is. [errorMessage] turns a
 * failure into the line the picker shows.
 */
class AddToListPicker(
    private val lists: SharedListsService,
    private val watchlist: WatchlistService,
    private val errorMessage: (Throwable) -> String,
) {
    fun load(target: AddToListTarget): Flow<AddToListUiState> =
        flow {
            val showsLists = Watchlist.pickerShowsLists(isSeerr = target is AddToListTarget.Seerr)
            emit(AddToListUiState(showsLists = showsLists))
            val item = (target as? AddToListTarget.Library)?.item
            coroutineScope {
                // The plugin is optional and an error here must not hide your lists: no row then.
                val view =
                    async {
                        if (item != null && !Watchlist.eligible(item)) return@async Result.success(null)
                        runCatchingCancellable { watchlist.view() }
                    }
                if (item == null) {
                    view
                        .await()
                        .onSuccess { v ->
                            emit(
                                if (v == null) {
                                    AddToListUiState(isLoading = false, showsLists = false, error = NO_WATCHLIST)
                                } else {
                                    AddToListUiState(isLoading = false, showsLists = false, watchlist = row(target, v))
                                },
                            )
                        }.onFailure { e -> emit(AddToListUiState(isLoading = false, showsLists = false, error = errorMessage(e))) }
                    return@coroutineScope
                }
                val stored = async { runCatchingCancellable { lists.storedId(item) }.getOrNull() }
                runCatchingCancellable { lists.allLists().filter { it.isMine } }
                    .onSuccess { mine ->
                        val storedId = stored.await()
                        val v = view.await().getOrNull()
                        emit(
                            AddToListUiState(
                                isLoading = false,
                                watchlist = v?.takeIf { Watchlist.pickerShowsWatchlist(item, it) }?.let { row(target, it) },
                                rows = mine.map { PickerRow(it, SharedListsService.contains(it.items, item, storedId)) },
                            ),
                        )
                    }.onFailure { e -> emit(AddToListUiState(isLoading = false, error = errorMessage(e))) }
            }
        }

    private fun row(
        target: AddToListTarget,
        view: WatchlistView,
    ): WatchlistRow {
        val already =
            when (target) {
                is AddToListTarget.Library -> Watchlist.alreadyOn(view, target.item)
                is AddToListTarget.Seerr -> {
                    val candidate = Watchlist.newEntry(target.details)
                    view.entries.any { Watchlist.matches(it, candidate) }
                }
            }
        return WatchlistRow(alreadyOn = already, count = view.entries.size)
    }

    companion object {
        const val NO_WATCHLIST = "Your server doesn't have a Watchlist."
    }
}
