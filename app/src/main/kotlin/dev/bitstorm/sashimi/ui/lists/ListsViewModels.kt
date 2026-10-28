package dev.bitstorm.sashimi.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.lists.AddResult
import dev.bitstorm.sashimi.core.lists.ListItem
import dev.bitstorm.sashimi.core.lists.NoEpisodesException
import dev.bitstorm.sashimi.core.lists.SharedList
import dev.bitstorm.sashimi.core.lists.SharedListsService
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinError
import dev.bitstorm.sashimi.core.util.ResumeRefreshGate
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a failed list call tells the user. */
internal fun listErrorMessage(e: Throwable): String =
    when (e) {
        is NoEpisodesException -> e.message ?: "This show has no episodes to add yet."
        is JellyfinError.Forbidden -> "Only the person who made this list can change it."
        is JellyfinError.HttpError ->
            if (e.statusCode ==
                404
            ) {
                "This list is no longer available."
            } else {
                "Couldn't reach your server. Try again."
            }
        else -> "Couldn't reach your server. Try again."
    }

// MARK: - Lists page

data class ListsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val mine: List<SharedList> = emptyList(),
    val others: List<SharedList> = emptyList(),
    val subscribed: Set<String> = emptySet(),
)

/** The Lists page: your lists, then everyone's public lists. */
class ListsViewModel(
    private val service: SharedListsService = ServiceLocator.sharedLists,
) : ViewModel() {
    private val _state = MutableStateFlow(ListsUiState())
    val state: StateFlow<ListsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val resumeGate = ResumeRefreshGate()

    init {
        load()
    }

    fun onScreenResumed() {
        if (resumeGate.onResume()) load()
    }

    fun load() {
        viewModelScope.launch {
            val subs = async { runCatchingCancellable { service.subscriptions() } }
            val lists = runCatchingCancellable { service.allLists() }
            lists
                .onSuccess { all ->
                    val subscribed =
                        subs
                            .await()
                            .getOrNull()
                            ?.map { it.id }
                            ?.toSet() ?: _state.value.subscribed
                    _state.value =
                        ListsUiState(
                            isLoading = false,
                            mine = all.filter { it.isMine },
                            others = all.filterNot { it.isMine },
                            subscribed = subscribed,
                        )
                }.onFailure { e -> _state.update { it.copy(isLoading = false, error = listErrorMessage(e)) } }
        }
    }

    /** A new, empty list; [onCreated] gets its id. */
    fun create(
        name: String,
        isPublic: Boolean,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatchingCancellable { service.create(name.trim(), isPublic) }
                .onSuccess { id ->
                    load()
                    onCreated(id)
                }.onFailure { _messages.tryEmit(listErrorMessage(it)) }
        }
    }
}

// MARK: - List detail

data class ListDetailUiState(
    val isLoading: Boolean = true,
    val list: SharedList? = null,
    /** 404: deleted, or made private by its owner. */
    val gone: Boolean = false,
    val error: String? = null,
    val subscribed: Boolean = false,
    /** Cards added since the viewer last opened it (subscribers only), fixed at open. */
    val newIds: Set<String> = emptySet(),
    val deleted: Boolean = false,
)

/** One list: the subscriber view, plus the owner's tools on your own list. */
class ListDetailViewModel(
    private val listId: String,
    private val service: SharedListsService = ServiceLocator.sharedLists,
) : ViewModel() {
    private val _state = MutableStateFlow(ListDetailUiState())
    val state: StateFlow<ListDetailUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val resumeGate = ResumeRefreshGate()

    init {
        load(markSeen = true)
    }

    fun onScreenResumed() {
        if (resumeGate.onResume()) load(markSeen = false)
    }

    private fun load(markSeen: Boolean) {
        viewModelScope.launch {
            val subs = async { runCatchingCancellable { service.subscriptions() } }
            val result = runCatchingCancellable { service.list(listId) }
            val list =
                result.getOrElse { e ->
                    _state.update { it.copy(isLoading = false, error = listErrorMessage(e)) }
                    return@launch
                }
            if (list == null) {
                _state.update { it.copy(isLoading = false, gone = true, list = null) }
                return@launch
            }
            val sub = subs.await().getOrNull()?.firstOrNull { it.id == listId }
            val newIds =
                if (markSeen && sub != null) {
                    val seen = sub.seen.toSet()
                    list.displayIds.filterNot { it in seen }.toSet()
                } else {
                    _state.value.newIds
                }
            _state.update {
                it.copy(isLoading = false, list = list, error = null, subscribed = sub != null, newIds = newIds)
            }
            // Opening a subscribed list means what it shows now has been seen.
            if (markSeen && sub != null && newIds.isNotEmpty()) {
                ServiceLocator.appScope.launch { runCatchingCancellable { service.markSeen(list) } }
            }
        }
    }

    private fun mutate(
        reload: Boolean = true,
        block: suspend (SharedList) -> Unit,
    ) {
        val list = _state.value.list ?: return
        viewModelScope.launch {
            runCatchingCancellable { block(list) }
                .onSuccess { if (reload) load(markSeen = false) }
                .onFailure { _messages.tryEmit(listErrorMessage(it)) }
        }
    }

    fun toggleSubscribe() {
        val subscribed = _state.value.subscribed
        _state.update { it.copy(subscribed = !subscribed) }
        mutate(reload = false) { list ->
            try {
                if (subscribed) service.unsubscribe(list.id) else service.subscribe(list)
            } catch (e: Exception) {
                _state.update { it.copy(subscribed = subscribed) }
                throw e
            }
        }
    }

    fun rename(name: String) = mutate { service.rename(it.id, name.trim()) }

    fun setPublic(isPublic: Boolean) = mutate { service.setPublic(it.id, isPublic) }

    fun remove(card: ListItem) = mutate { service.remove(it.id, card) }

    /** Moves [card] one place up (-1) or down (+1). */
    fun move(
        card: ListItem,
        by: Int,
    ) {
        val items = _state.value.list?.items ?: return
        val target = items.getOrNull(items.indexOf(card) + by) ?: return
        mutate { service.move(it.id, card, target.firstEntryIndex) }
    }

    fun delete() {
        mutate(reload = false) {
            service.delete(it.id)
            runCatchingCancellable { service.unsubscribe(it.id) }
            _state.update { s -> s.copy(deleted = true) }
        }
    }

    /** A random title from the list, for Shuffle. */
    fun shuffleTarget(): String? =
        _state.value.list
            ?.items
            ?.randomOrNull()
            ?.item
            ?.id

    class Factory(
        private val listId: String,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ListDetailViewModel(listId) as T
    }
}

// MARK: - Add to List picker

/** One of your lists in the picker. */
data class PickerRow(
    val list: SharedList,
    val alreadyOn: Boolean,
)

data class AddToListUiState(
    val isLoading: Boolean = true,
    val rows: List<PickerRow> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
)

/** The Add to List picker for one title: your lists, marked where it is already on. */
class AddToListViewModel(
    private val item: BaseItemDto,
    private val service: SharedListsService = ServiceLocator.sharedLists,
) : ViewModel() {
    private val _state = MutableStateFlow(AddToListUiState())
    val state: StateFlow<AddToListUiState> = _state.asStateFlow()

    /**
     * Reads your lists afresh. Called each time the picker opens: the
     * ViewModel outlives the dialog (it belongs to the detail page), and a
     * list made or changed since must show.
     */
    fun load() {
        _state.value = AddToListUiState()
        viewModelScope.launch {
            val stored = async { runCatchingCancellable { service.storedId(item) }.getOrNull() }
            runCatchingCancellable { service.allLists().filter { it.isMine } }
                .onSuccess { mine ->
                    val storedId = stored.await()
                    _state.value =
                        AddToListUiState(
                            isLoading = false,
                            rows = mine.map { PickerRow(it, SharedListsService.contains(it.items, item, storedId)) },
                        )
                }.onFailure { e -> _state.value = AddToListUiState(isLoading = false, error = listErrorMessage(e)) }
        }
    }

    /** Adds to [list]; [done] gets the line to show. */
    fun add(
        list: SharedList,
        done: (String) -> Unit,
    ) = run(done) {
        when (service.add(list.id, item)) {
            AddResult.ADDED -> "Added to ${list.name}"
            AddResult.ALREADY_ON_LIST -> "Already on ${list.name}"
        }
    }

    fun createAndAdd(
        name: String,
        isPublic: Boolean,
        done: (String) -> Unit,
    ) = run(done) {
        service.create(name.trim(), isPublic, firstItem = item)
        "Added to ${name.trim()}"
    }

    private fun run(
        done: (String) -> Unit,
        block: suspend () -> String,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = runCatchingCancellable { block() }.getOrElse { listErrorMessage(it) }
            _state.update { it.copy(busy = false) }
            done(message)
        }
    }

    class Factory(
        private val item: BaseItemDto,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AddToListViewModel(item) as T
    }
}
