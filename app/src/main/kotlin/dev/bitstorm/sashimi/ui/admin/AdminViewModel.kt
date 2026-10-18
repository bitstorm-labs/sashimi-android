package dev.bitstorm.sashimi.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.admin.AdminDashboardSource
import dev.bitstorm.sashimi.core.admin.AdminGate
import dev.bitstorm.sashimi.core.admin.AdminLoad
import dev.bitstorm.sashimi.core.admin.HealthSnapshot
import dev.bitstorm.sashimi.core.admin.SessionInfoDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Server, Activity and Health screens. Both polls are shared with
 * `WhileSubscribed(0)` and collected with `collectAsStateWithLifecycle`, so they
 * run only while a screen showing them is visible: navigating away or
 * backgrounding the app stops the requests at once.
 */
class AdminViewModel(
    private val client: JellyfinClient,
    private val gate: AdminGate,
) : ViewModel() {
    private val source = AdminDashboardSource(client)

    private val _forbidden = MutableStateFlow(false)

    /** True once the server said this account is not an admin; the screen closes. */
    val forbidden: StateFlow<Boolean> = _forbidden.asStateFlow()

    val sessions: StateFlow<AdminLoad<List<SessionInfoDto>>?> =
        source.sessions().onEach(::watchForbidden).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    val health: StateFlow<AdminLoad<HealthSnapshot>?> =
        source.health().onEach(::watchForbidden).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line result of Stop / Send message, shown as a snackbar. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    fun stop(session: SessionInfoDto) = act("Stopped ${session.userName ?: "the stream"}'s stream.") { client.stopSession(session.id) }

    fun sendMessage(
        session: SessionInfoDto,
        text: String,
    ) = act("Message sent to ${session.userName ?: "the viewer"}.") { client.sendSessionMessage(session.id, text.trim()) }

    private fun act(
        success: String,
        call: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _notice.value =
                try {
                    call()
                    success
                } catch (e: CancellationException) {
                    throw e
                } catch (e: JellyfinError.Forbidden) {
                    watchForbidden(AdminLoad.Forbidden)
                    null
                } catch (e: JellyfinError) {
                    "Couldn't do that: ${e.userMessage}"
                }
        }
    }

    private fun watchForbidden(load: AdminLoad<*>) {
        if (load is AdminLoad.Forbidden) {
            _forbidden.value = true
            gate.forbidden()
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AdminViewModel(ServiceLocator.client, ServiceLocator.adminGate) as T
    }
}
