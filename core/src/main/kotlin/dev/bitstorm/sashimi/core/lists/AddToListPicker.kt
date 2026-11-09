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
    /** Null when the Watchlist hasn't been read (yet, or it failed): the row still takes adds. */
    val count: Int?,
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
    /**
     * The Watchlist row never waits on the Watchlist read: your lists show as
     * soon as they arrive, and a read that is slow, retrying or failed leaves
     * the row in place (only a 404 removes it). An add then shows any error.
     */
    fun load(target: AddToListTarget): Flow<AddToListUiState> =
        flow {
            val showsLists = Watchlist.pickerShowsLists(isSeerr = target is AddToListTarget.Seerr)
            emit(AddToListUiState(showsLists = showsLists))
            val item = (target as? AddToListTarget.Library)?.item
            coroutineScope {
                val read =
                    async {
                        if (item != null && !Watchlist.eligible(item)) WatchlistRead.Absent else watchlist.read()
                    }
                if (item == null) {
                    // A Seerr title: the Watchlist is all there is, so the row shows at once and fills in.
                    emit(seerrState(target, WatchlistRead.Pending))
                    emit(seerrState(target, read.await()))
                    return@coroutineScope
                }
                val stored = async { runCatchingCancellable { lists.storedId(item) }.getOrNull() }
                val mine =
                    runCatchingCancellable { lists.allLists().filter { it.isMine } }.getOrElse { e ->
                        read.cancel()
                        emit(AddToListUiState(isLoading = false, error = errorMessage(e)))
                        return@coroutineScope
                    }
                val storedId = stored.await()
                val ready =
                    AddToListUiState(
                        isLoading = false,
                        rows = mine.map { PickerRow(it, SharedListsService.contains(it.items, item, storedId)) },
                    )
                if (!read.isCompleted) emit(ready.copy(watchlist = row(target, item, WatchlistRead.Pending)))
                emit(ready.copy(watchlist = row(target, item, read.await())))
            }
        }

    private fun seerrState(
        target: AddToListTarget,
        read: WatchlistRead,
    ): AddToListUiState =
        if (read is WatchlistRead.Absent) {
            AddToListUiState(isLoading = false, showsLists = false, error = NO_WATCHLIST)
        } else {
            AddToListUiState(isLoading = false, showsLists = false, watchlist = row(target, null, read))
        }

    /** Null hides the row. Before (or without) a read there's no count and nothing marked. */
    private fun row(
        target: AddToListTarget,
        item: BaseItemDto?,
        read: WatchlistRead,
    ): WatchlistRow? {
        if (!Watchlist.pickerShowsWatchlist(item, read)) return null
        val view = (read as? WatchlistRead.Ready)?.view ?: return WatchlistRow(alreadyOn = false, count = null)
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
