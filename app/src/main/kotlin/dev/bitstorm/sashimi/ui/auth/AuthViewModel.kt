package dev.bitstorm.sashimi.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.normalizeServerUrl
import dev.bitstorm.sashimi.core.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the two-step connect flow (ported from MobileAuthView). */
data class AuthUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val showLogin: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val normalizedUrl: String? = null,
)

class AuthViewModel(
    private val client: JellyfinClient,
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value) }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    /** Jump straight to the password step for a re-auth (prefilled server + user). */
    fun prefill(
        serverUrl: String,
        username: String,
    ) {
        client.configure(serverUrl = serverUrl)
        _state.update {
            it.copy(
                serverUrl = serverUrl,
                username = username,
                normalizedUrl = serverUrl,
                showLogin = true,
            )
        }
    }

    fun useDifferentServer() =
        _state.update {
            it.copy(showLogin = false, serverUrl = "", normalizedUrl = null, password = "")
        }

    /** Step 1: normalize + probe the server with getPublicSystemInfo. */
    fun connect() {
        val raw = _state.value.serverUrl
        val normalized = normalizeServerUrl(raw)
        if (normalized == null) {
            _state.update { it.copy(errorMessage = "Invalid server URL") }
            return
        }
        _state.update { it.copy(isConnecting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                client.configure(serverUrl = normalized)
                client.getPublicSystemInfo() // reachability probe
                _state.update {
                    it.copy(isConnecting = false, normalizedUrl = normalized, showLogin = true)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = "Connection failed: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    /** Step 2: sign in against the probed server. [onComplete] dismisses a sheet. */
    fun signIn(onComplete: () -> Unit) {
        val url = _state.value.normalizedUrl ?: return
        val username = _state.value.username
        val password = _state.value.password
        if (username.isEmpty()) return
        _state.update { it.copy(isConnecting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                session.login(serverUrl = url, username = username, password = password)
                _state.update { it.copy(isConnecting = false) }
                onComplete()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = "Sign in failed: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }
}
