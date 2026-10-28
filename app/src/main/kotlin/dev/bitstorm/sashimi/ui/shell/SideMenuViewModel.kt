package dev.bitstorm.sashimi.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.home.SideMenu
import dev.bitstorm.sashimi.core.home.SideMenuItem
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The side menu's items (#187): [SideMenu.items] over the Home rows and the
 * active server's libraries. The libraries are fetched whenever the active
 * server, its user or connectivity changes; until they arrive the rows' own
 * libraries stand in, so the menu is complete from the first frame.
 */
class SideMenuViewModel(
    private val rowSettings: HomeRowSettings = ServiceLocator.homeRowSettings,
) : ViewModel() {
    /** Null until the server has answered for the current server/user. */
    private val serverLibraries = MutableStateFlow<List<JellyfinLibrary>?>(null)

    init {
        val session = ServiceLocator.session
        viewModelScope.launch {
            combine(session.activeServerId, session.currentUser, ServiceLocator.networkMonitor.isOnline) { server, user, online ->
                Triple(server, user?.id, online)
            }.distinctUntilChanged().collect { (_, _, online) ->
                if (!online) return@collect
                serverLibraries.value = null
                val libraries =
                    runCatchingCancellable { ServiceLocator.client.getUserViews() }.getOrNull() ?: return@collect
                val media = libraries.filter { SideMenu.isMediaLibrary(it.collectionType) }
                // Same reconcile Home does, so the settings screen lists them too.
                rowSettings.updateLibraries(media)
                serverLibraries.value = media
            }
        }
    }

    val items: StateFlow<List<SideMenuItem>> =
        combine(
            rowSettings.rows,
            serverLibraries,
            ServiceLocator.networkMonitor.isOnline,
            ServiceLocator.appSettings.showLists,
        ) { rows, libraries, online, showLists ->
            SideMenu.items(rows, libraries ?: provisional(rows), online, showLists)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SideMenu.ALWAYS_SHOWN)

    private fun provisional(rows: List<HomeRowConfig>): List<JellyfinLibrary> =
        rows.mapNotNull { row ->
            val id = row.libraryId ?: return@mapNotNull null
            if (!row.isSideMenuDestination) null else JellyfinLibrary(id = id, name = row.libraryName ?: "", collectionType = null)
        }
}
