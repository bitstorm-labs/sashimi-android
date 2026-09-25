package dev.bitstorm.sashimi.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.model.PersonInfo
import dev.bitstorm.sashimi.core.person.Filmography
import dev.bitstorm.sashimi.core.person.PersonFilmographyError
import dev.bitstorm.sashimi.core.person.PersonFilmographyService
import dev.bitstorm.sashimi.core.person.ServerMediaGroup
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.nav.PersonRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FilmographyState {
    data object Loading : FilmographyState

    /** No network: say so, rather than show an empty filmography. */
    data object Offline : FilmographyState

    data class Failed(
        val message: String,
    ) : FilmographyState

    /**
     * [failedServerCount] > 0 means some servers answered and some did not; the
     * list is shown, with a note that it may be incomplete.
     */
    data class Loaded(
        val groups: List<ServerMediaGroup>,
        val failedServerCount: Int,
    ) : FilmographyState
}

/**
 * Loads a person's filmography across every saved server. Scoped to the person
 * route's back-stack entry, so returning from a title (navigation-compose
 * recomposes this entry on pop) shows the already-loaded list instead of
 * fanning out to every server again.
 */
class PersonViewModel(
    private val route: PersonRoute,
    private val service: PersonFilmographyService,
    private val isOnline: () -> Boolean,
    private val serverOrder: () -> List<String>,
) : ViewModel() {
    val person =
        PersonInfo(
            id = route.personId,
            name = route.name,
            role = route.role,
            type = route.type,
            primaryImageTag = route.primaryImageTag,
        )

    private val _state = MutableStateFlow<FilmographyState>(FilmographyState.Loading)
    val state: StateFlow<FilmographyState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        if (!isOnline()) {
            _state.value = FilmographyState.Offline
            return
        }
        _state.value = FilmographyState.Loading
        job =
            viewModelScope.launch {
                _state.value =
                    try {
                        val load = service.load(person, route.originServerId)
                        FilmographyState.Loaded(
                            groups =
                                Filmography.build(
                                    results = load.results,
                                    preferredServerId = route.originServerId,
                                    serverOrder = serverOrder(),
                                    excludeTitleKey = route.excludeTitleKey,
                                    excludeItemId = route.excludeItemId,
                                ),
                            failedServerCount = load.failedServerCount,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PersonFilmographyError) {
                        FilmographyState.Failed(e.message ?: GENERIC_FAILURE)
                    } catch (e: Exception) {
                        FilmographyState.Failed(GENERIC_FAILURE)
                    }
            }
    }

    class Factory(
        private val route: PersonRoute,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val session = ServiceLocator.session
            val service =
                PersonFilmographyService(
                    servers = { session.servers.value },
                    tokenFor = { session.tokenFor(it.id) },
                    clientFor = { server, token -> ServiceLocator.serverClients.clientFor(server, token) },
                )
            return PersonViewModel(
                route = route,
                service = service,
                isOnline = { ServiceLocator.networkMonitor.isOnline.value },
                serverOrder = { session.servers.value.map { it.id } },
            ) as T
        }
    }

    private companion object {
        const val GENERIC_FAILURE = "Check your server connection and try again."
    }
}
