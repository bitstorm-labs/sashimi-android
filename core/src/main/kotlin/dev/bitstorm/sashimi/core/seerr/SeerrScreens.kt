package dev.bitstorm.sashimi.core.seerr

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

// The request screens' state, ported from sashimi-apple Shared/ViewModels/Seerr*.
// Plain classes (no Android ViewModel) so the rules run in :core unit tests;
// :app wraps each in a ViewModel that owns the coroutine scope. Each one is
// driven from a single thread (the main dispatcher), like Apple's @MainActor.

/**
 * Home's requests row: everyone's recent requests for those Seerr lets see
 * them (#855), otherwise the person's own.
 */
class SeerrHomeRequests(
    private val session: SeerrSession,
) {
    private val _rows = MutableStateFlow<List<SeerrRequestRow>>(emptyList())
    val rows: StateFlow<List<SeerrRequestRow>> = _rows.asStateFlow()

    var errorMessage: String? = null
        private set

    private val titles = SeerrTitleCache()

    /** Everyone's requests (MANAGE_REQUESTS or REQUEST_VIEW), not just mine. */
    val showsEveryone: Boolean get() = session.permissions.canViewAllRequests

    val title: String get() = if (showsEveryone) "Recent Requests" else "Your Requests"

    /** Who asked, under the title; only on everyone's row ("Your Requests" would always be the person). */
    fun requester(row: SeerrRequestRow): String? = if (showsEveryone) row.requesterName else null

    /** Only while something waits or arrived in the last week. */
    fun homeRows(now: Instant = Instant.now()): List<SeerrRequestRow> =
        _rows.value.filter { SeerrRequestState.showsOnHome(it.request, now) }

    suspend fun load() {
        val client = session.client
        val user = session.user
        if (client == null || user == null) {
            _rows.value = emptyList()
            return
        }
        try {
            val requests =
                if (showsEveryone) {
                    client.requestPage(SeerrRequestFilter.ALL, take = 50, skip = 0, requestedBy = null).results
                } else {
                    client.requests(requestedBy = user.id)
                }
            titles.fill(requests, client)
            val now = Instant.now()
            // Newest first, so the first request per title is the one shown:
            // several people asking for one title is still one poster.
            val seen = mutableSetOf<String>()
            _rows.value = requests.mapNotNull { SeerrRequestRow.of(it, titles, now) }.filter { seen.add("${it.kind.wire}-${it.tmdbId}") }
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message
            session.handle(e)
        }
    }
}

/**
 * The Requests section of the Requests page (#845), like Seerr web's
 * /requests: one filter, newest first, the person's own or (with
 * MANAGE_REQUESTS or REQUEST_VIEW) everyone's, loaded a page at a time.
 */
class SeerrRequestList(
    private val session: SeerrSession,
    val pageSize: Int = 20,
) {
    data class State(
        val rows: List<SeerrRequestRow> = emptyList(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val canLoadMore: Boolean = false,
        /** Everyone's requests by state; only for those who may see everyone's. */
        val count: SeerrRequestCount? = null,
        /** Requests with an action in flight. */
        val busy: Set<Int> = emptySet(),
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var filter: SeerrRequestFilter = SeerrRequestFilter.ALL
    var scope: SeerrRequestScope = SeerrRequestScope.EVERYONE

    private val titles = SeerrTitleCache()
    private var fetched = 0
    private var generation = 0

    val rows: List<SeerrRequestRow> get() = _state.value.rows

    /** The Everyone / Mine switch; hidden for anyone who can only see their own. */
    val canChooseScope: Boolean get() = session.permissions.canViewAllRequests

    val effectiveScope: SeerrRequestScope get() = if (canChooseScope) scope else SeerrRequestScope.MINE

    val showsRequester: Boolean get() = effectiveScope == SeerrRequestScope.EVERYONE

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    /** From the first page again, for the current filter and scope. */
    suspend fun reload() {
        generation += 1
        fetched = 0
        _state.update { it.copy(canLoadMore = false) }
        loadPage(replacing = true)
        val client = session.client
        val count =
            if (canChooseScope && client != null) {
                try {
                    client.requestCount()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        _state.update { it.copy(count = count) }
    }

    /** Paging: call as each row appears; the next page loads near the end. */
    suspend fun loadMoreIfNeeded(row: SeerrRequestRow) {
        val s = _state.value
        if (!s.canLoadMore || s.isLoading) return
        val index = s.rows.indexOfFirst { it.id == row.id }
        if (index < 0 || index < s.rows.size - 5) return
        loadPage(replacing = false)
    }

    fun actions(row: SeerrRequestRow): List<SeerrRequestAction> {
        val isPending = row.request.requestStatus == SeerrRequestStatus.PENDING
        if (session.permissions.canManageRequests) {
            return (if (isPending) listOf(SeerrRequestAction.APPROVE, SeerrRequestAction.DECLINE) else emptyList()) +
                SeerrRequestAction.DELETE
        }
        val isMine = row.request.requestedBy?.id == session.user?.id
        return if (isMine && isPending) listOf(SeerrRequestAction.CANCEL) else emptyList()
    }

    /** Runs one action. Nothing is sent for an action the person lacks. */
    suspend fun perform(
        action: SeerrRequestAction,
        row: SeerrRequestRow,
    ) {
        val client = session.client ?: return
        if (action !in actions(row) || row.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + row.id, errorMessage = null) }
        try {
            when (action) {
                SeerrRequestAction.APPROVE -> {
                    client.approveRequest(row.id)
                    update(row, SeerrRequestStatus.APPROVED)
                }
                SeerrRequestAction.DECLINE -> {
                    client.declineRequest(row.id)
                    update(row, SeerrRequestStatus.DECLINED)
                }
                SeerrRequestAction.CANCEL, SeerrRequestAction.DELETE -> {
                    client.cancelRequest(row.id)
                    _state.update { s -> s.copy(rows = s.rows.filterNot { it.id == row.id }) }
                }
            }
            session.requestsChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.message) }
            session.handle(e)
        } finally {
            _state.update { it.copy(busy = it.busy - row.id) }
        }
    }

    private suspend fun loadPage(replacing: Boolean) {
        val client = session.client
        val user = session.user
        if (client == null || user == null) {
            _state.update { it.copy(rows = emptyList(), hasLoaded = true) }
            return
        }
        val generation = generation
        val requestedBy = if (effectiveScope == SeerrRequestScope.MINE) user.id else null
        _state.update { it.copy(isLoading = true) }
        try {
            val page = client.requestPage(filter, take = pageSize, skip = fetched, requestedBy = requestedBy)
            titles.fill(page.results, client)
            if (generation != this.generation) return
            val now = Instant.now()
            val newRows = page.results.mapNotNull { SeerrRequestRow.of(it, titles, now) }
            fetched += page.results.size
            val total = page.pageInfo?.results
            _state.update { s ->
                val known = s.rows.map { it.id }.toSet()
                s.copy(
                    rows = if (replacing) newRows else s.rows + newRows.filterNot { it.id in known },
                    canLoadMore = if (total != null) fetched < total else page.results.size == pageSize,
                    errorMessage = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != this.generation) return
            _state.update { s -> s.copy(rows = if (replacing) emptyList() else s.rows, canLoadMore = false, errorMessage = e.message) }
            session.handle(e)
        } finally {
            _state.update { it.copy(isLoading = false, hasLoaded = true) }
        }
    }

    /** The new state locally until the reload lands; a row the filter no longer matches leaves. */
    private fun update(
        row: SeerrRequestRow,
        status: SeerrRequestStatus,
    ) {
        _state.update { s ->
            if (filter == SeerrRequestFilter.PENDING) {
                s.copy(rows = s.rows.filterNot { it.id == row.id })
            } else {
                s.copy(rows = s.rows.map { if (it.id == row.id) it.withStatus(status) else it })
            }
        }
    }
}

/** One Discover row: a slider's titles so far and where its paging is. */
data class SeerrDiscoverRow(
    val slider: SeerrDiscoverSlider,
    val items: List<SeerrSearchItem>,
    val page: Int,
    val totalPages: Int,
) {
    val title: String get() = slider.title
    val canLoadMore: Boolean get() = slider != SeerrDiscoverSlider.RECENT_REQUESTS && page < totalPages
}

/**
 * The Discover section of the Requests page (#845): Recent Requests,
 * Trending, Popular and Upcoming Movies and Series, and the Plex Watchlist.
 * Rows that come back empty are left out.
 */
class SeerrDiscover(
    private val session: SeerrSession,
) {
    data class State(
        val rows: List<SeerrDiscoverRow> = emptyList(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val titles = SeerrTitleCache()
    private val loadingMore = mutableSetOf<SeerrDiscoverSlider>()

    val rows: List<SeerrDiscoverRow> get() = _state.value.rows

    suspend fun load() {
        val client = session.client
        if (client == null) {
            _state.value = State(hasLoaded = true)
            return
        }
        _state.update { it.copy(isLoading = true) }
        val userId = session.user?.id
        val results =
            coroutineScope {
                SeerrDiscoverSlider.entries
                    .map { slider ->
                        async {
                            try {
                                Result.success(firstPage(slider, client, userId))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }.awaitAll()
            }
        val rows = results.mapNotNull { it.getOrNull() }.filter { it.items.isNotEmpty() }
        val firstError = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        _state.value = State(rows = rows, hasLoaded = true, errorMessage = if (rows.isEmpty()) firstError?.message else null)
        if (rows.isEmpty() && firstError != null) session.handle(firstError)
    }

    /** Paging: call as each poster appears; the row's next page loads when it's among the last few. */
    suspend fun loadMoreIfNeeded(
        slider: SeerrDiscoverSlider,
        item: SeerrSearchItem,
    ) {
        val row = _state.value.rows.firstOrNull { it.slider == slider } ?: return
        if (!row.canLoadMore || slider in loadingMore) return
        val index = row.items.indexOfFirst { it.id == item.id }
        if (index < 0 || index < row.items.size - 5) return
        val client = session.client ?: return
        loadingMore += slider
        try {
            val page =
                try {
                    client.discover(slider, row.page + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return
                }
            val items = items(page.results, slider, client, session.user?.id)
            _state.update { s ->
                s.copy(
                    rows =
                        s.rows.map { current ->
                            if (current.slider != slider) return@map current
                            val known = current.items.map { it.id }.toSet()
                            current.copy(
                                items = current.items + items.filterNot { it.id in known },
                                page = page.page,
                                totalPages = page.totalPages,
                            )
                        },
                )
            }
        } finally {
            loadingMore -= slider
        }
    }

    private suspend fun firstPage(
        slider: SeerrDiscoverSlider,
        client: SeerrClient,
        userId: Int?,
    ): SeerrDiscoverRow {
        if (slider == SeerrDiscoverSlider.RECENT_REQUESTS) {
            val page = client.requestPage(SeerrRequestFilter.ALL, take = RECENT_REQUESTS_COUNT, skip = 0, requestedBy = null)
            titles.fill(page.results, client)
            val results =
                page.results.mapNotNull { request ->
                    val target = SeerrTitleCache.target(request) ?: return@mapNotNull null
                    val known = titles.entry(target)
                    SeerrSearchResult(
                        tmdbId = target.tmdbId,
                        kind = target.kind,
                        title = known?.title ?: "Unknown title",
                        posterPath = known?.posterPath,
                        mediaInfo = request.media ?: known?.mediaInfo,
                    )
                }
            return SeerrDiscoverRow(slider, unique(results, userId), 1, 1)
        }
        val page = client.discover(slider)
        return SeerrDiscoverRow(slider, items(page.results, slider, client, userId), page.page, page.totalPages)
    }

    /** The watchlist has no posters or media state: they come from each title's details. */
    private suspend fun items(
        results: List<SeerrSearchResult>,
        slider: SeerrDiscoverSlider,
        client: SeerrClient,
        userId: Int?,
    ): List<SeerrSearchItem> {
        if (slider != SeerrDiscoverSlider.WATCHLIST) return unique(results, userId)
        titles.fillTargets(results.map(SeerrTarget::of), client)
        val filled =
            results.map { result ->
                val known = titles.entry(SeerrTarget.of(result)) ?: return@map result
                result.copy(
                    posterPath = result.posterPath ?: known.posterPath,
                    mediaInfo = result.mediaInfo ?: known.mediaInfo,
                    title = result.title.ifEmpty { known.title },
                )
            }
        return unique(filled, userId)
    }

    private fun unique(
        results: List<SeerrSearchResult>,
        userId: Int?,
    ): List<SeerrSearchItem> {
        val seen = mutableSetOf<String>()
        return results.filter { seen.add(it.id) }.map { SeerrSearchItem(it, SeerrBadge.of(it.mediaInfo, userId)) }
    }

    companion object {
        /** Seerr web's Recent Requests slider takes ten. */
        const val RECENT_REQUESTS_COUNT = 10
    }
}

/**
 * Search's "Not in your library" row. Silent unless the person has a Seerr
 * account: no account, no row and no request to Seerr.
 */
class SeerrSearch(
    private val session: SeerrSession,
    /** The Requests page's own search lists everything, what's on the server included. */
    private val includesAvailable: Boolean = false,
) {
    private val _items = MutableStateFlow<List<SeerrSearchItem>>(emptyList())
    val items: StateFlow<List<SeerrSearchItem>> = _items.asStateFlow()

    val isVisible: Boolean get() = session.isActive && _items.value.isNotEmpty()

    val sectionTitle: String get() = "Not in your library · from ${session.applicationTitle.value}"

    /** [library] is what the library row shows for the same query, so nothing appears in both. */
    suspend fun search(
        query: String,
        library: List<BaseItemDto>,
    ) {
        val trimmed = query.trim()
        val client = session.client
        if (!session.isActive || client == null || trimmed.isEmpty()) {
            _items.value = emptyList()
            return
        }
        try {
            val page = client.search(trimmed)
            val userId = session.user?.id
            val results =
                if (includesAvailable) {
                    SeerrSearchMerge.unique(
                        page.results,
                    )
                } else {
                    SeerrSearchMerge.requestable(page.results, library)
                }
            _items.value = results.map { SeerrSearchItem(it, SeerrBadge.of(it.mediaInfo, userId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _items.value = emptyList()
            session.handle(e)
        }
    }

    fun clear() {
        _items.value = emptyList()
    }
}

/**
 * The request page for a title that isn't on the server: its details from
 * Seerr, and what the main button does.
 */
class SeerrTitle(
    val target: SeerrTarget,
    private val session: SeerrSession,
    /** Finds the library's copy of a title once it has arrived. */
    private val libraryLookup: suspend (tmdbId: Int, kind: SeerrMediaKind, title: String) -> BaseItemDto?,
) {
    sealed interface Action {
        data object Loading : Action

        /** A movie: one press. */
        data object Request : Action

        /** A show: opens the season picker. */
        data object ChooseSeasons : Action

        /** Requested already: "Requested · waiting for approval", "Coming soon"... */
        data class Status(
            val text: String,
        ) : Action

        data class Failed(
            val text: String,
        ) : Action
    }

    data class State(
        val details: SeerrTitleDetails? = null,
        val action: Action = Action.Loading,
        /** Set once the title is on the server: the page offers the library title. */
        val libraryItem: BaseItemDto? = null,
        val isSubmitting: Boolean = false,
        /** "2 of 5 movie requests left · resets over 7 days", only with a limit. */
        val quotaNote: String? = null,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val action: Action get() = _state.value.action

    val badge: SeerrBadge get() = SeerrBadge.of(_state.value.details?.mediaInfo, session.user?.id)

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    suspend fun load() {
        val client = session.client
        if (client == null) {
            _state.update { it.copy(action = Action.Failed("Sign in to ${session.applicationTitle.value} in Settings › Requests.")) }
            return
        }
        try {
            val details = client.details(target.kind, target.tmdbId)
            val action = action(details, seasonPlan(details), session.user?.id)
            _state.update { it.copy(details = details, action = action) }
            loadQuota(client, action)
            val status = details.mediaInfo?.mediaStatus
            if (status == SeerrMediaStatus.AVAILABLE || status == SeerrMediaStatus.PARTIALLY_AVAILABLE) {
                val item = libraryLookup(target.tmdbId, target.kind, details.title)
                _state.update { it.copy(libraryItem = item) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.details == null) _state.update { it.copy(action = Action.Failed(e.message ?: "Couldn't load this title.")) }
            session.handle(e)
        }
    }

    /** Best effort: no line if the quota can't be read. */
    private suspend fun loadQuota(
        client: SeerrClient,
        action: Action,
    ) {
        val userId = session.user?.id
        val note =
            if ((action == Action.Request || action == Action.ChooseSeasons) && userId != null) {
                try {
                    client.quota(userId).summary(target.kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        _state.update { it.copy(quotaNote = note) }
    }

    fun seasonPlan(): SeerrSeasonPlan? = _state.value.details?.let(::seasonPlan)

    suspend fun requestMovie() = submit { it.requestMovie(target.tmdbId) }

    suspend fun requestSeasons(plan: SeerrSeasonPlan) = submit { it.requestShow(target.tmdbId, plan.requestSeasons) }

    private suspend fun submit(send: suspend (SeerrClient) -> SeerrRequest) {
        val client = session.client ?: return
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        try {
            send(client)
            session.requestsChanged()
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.message) }
            session.handle(e)
        } finally {
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    private fun seasonPlan(details: SeerrTitleDetails): SeerrSeasonPlan? =
        if (details.kind ==
            SeerrMediaKind.TV
        ) {
            SeerrSeasonPlan.of(details, partialRequestsEnabled = session.partialRequestsEnabled)
        } else {
            null
        }

    companion object {
        fun action(
            details: SeerrTitleDetails,
            plan: SeerrSeasonPlan?,
            userId: Int?,
        ): Action {
            if (plan != null && plan.hasMissingSeasons) return Action.ChooseSeasons
            return when (SeerrBadge.of(details.mediaInfo, userId)) {
                SeerrBadge.AVAILABLE -> Action.Status("On your server")
                SeerrBadge.COMING -> Action.Status("Coming soon")
                SeerrBadge.PENDING -> Action.Status("Requested · waiting for approval")
                SeerrBadge.REQUEST, SeerrBadge.DECLINED ->
                    when {
                        plan == null -> Action.Request
                        plan.seasons.any { it.state.isPickable } -> Action.ChooseSeasons
                        else -> Action.Status("Every season is on the server or requested")
                    }
            }
        }

        /**
         * For a library show's menu: a target when Seerr says some aired seasons
         * aren't on the server and nobody has asked. Null (and no request to
         * Seerr) without an account or a TMDB id.
         */
        suspend fun missingSeasonsTarget(
            item: BaseItemDto,
            session: SeerrSession,
        ): SeerrTarget? {
            if (item.type != ItemType.SERIES) return null
            val tmdbId = item.tmdbId ?: return null
            val client = session.client ?: return null
            if (!session.isActive) return null
            val details =
                try {
                    client.details(SeerrMediaKind.TV, tmdbId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return null
                }
            val plan = SeerrSeasonPlan.of(details, partialRequestsEnabled = session.partialRequestsEnabled)
            return if (plan.hasMissingSeasons) SeerrTarget(SeerrMediaKind.TV, tmdbId, details.title, details.posterPath) else null
        }
    }
}
