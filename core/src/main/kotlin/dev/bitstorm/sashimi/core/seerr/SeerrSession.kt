package dev.bitstorm.sashimi.core.seerr

import dev.bitstorm.sashimi.core.session.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Where the session is. Port of sashimi-apple SeerrAuthState. */
sealed interface SeerrAuthState {
    /** No account: nothing about requests appears anywhere in the app. */
    data object NotConfigured : SeerrAuthState

    /** A sign-in is being exchanged for a session. */
    data object SigningIn : SeerrAuthState

    /** Plex's sign-in page is open and the PIN is being polled. */
    data class AwaitingPlex(
        val code: String,
    ) : SeerrAuthState

    data class SignedIn(
        val account: SeerrUser,
    ) : SeerrAuthState

    /** The server stopped accepting the session: Settings asks to sign in again; elsewhere requests hide. */
    data object Expired : SeerrAuthState

    val user: SeerrUser? get() = (this as? SignedIn)?.account

    /** Requests appear in the side menu and Libraries: signed in, or expired so the page can say so. */
    val showsRequestsEntry: Boolean get() = this is SignedIn || this == Expired
}

/** Non-secret Seerr settings (server, last user, Plex client id). The cookie goes in a [TokenStore]. */
interface SeerrSettingsStore {
    fun getString(key: String): String?

    fun getInt(key: String): Int?

    fun putString(
        key: String,
        value: String?,
    )

    fun putInt(
        key: String,
        value: Int?,
    )
}

/**
 * The person's Seerr account: which server, whether they're signed in, and
 * the client the request screens use. Each person signs in as themselves
 * (Plex or a Seerr login), so approvals and quotas work as on the website.
 * No admin API key is ever stored. Port of sashimi-apple SeerrSession.
 */
class SeerrSession(
    private val settings: SeerrSettingsStore,
    private val cookies: TokenStore,
    private val pollIntervalMillis: Long = 2_000,
    private val clientFactory: (baseUrl: String, cookie: String?) -> SeerrClient = { url, cookie -> SeerrClient(url, cookie) },
    private val plexFactory: (clientId: String) -> PlexPinClient = { PlexPinClient(it, "1.0") },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SeerrAuthState>(SeerrAuthState.NotConfigured)
    val state: StateFlow<SeerrAuthState> = _state.asStateFlow()

    private val _serverUrl =
        MutableStateFlow(settings.getString(KEY_SERVER)?.let(::normalizedServerUrl) ?: DEFAULT_SERVER)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    /** The server's own name ("MondoVision"), for "from MondoVision". */
    private val _applicationTitle = MutableStateFlow(settings.getString(KEY_TITLE) ?: "Seerr")
    val applicationTitle: StateFlow<String> = _applicationTitle.asStateFlow()

    var partialRequestsEnabled: Boolean = true
        private set
    var localLoginEnabled: Boolean = true
        private set

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Bumped after a request is sent, approved, declined or cancelled, so open lists reload. */
    private val _requestsRevision = MutableStateFlow(0)
    val requestsRevision: StateFlow<Int> = _requestsRevision.asStateFlow()

    @Volatile
    var client: SeerrClient? = null
        private set

    val isActive: Boolean get() = _state.value.user != null

    /** What the signed-in account may do; none while signed out. */
    val permissions: SeerrPermissions get() = _state.value.user?.permissionSet ?: SeerrPermissions.NONE

    val user: SeerrUser? get() = _state.value.user

    fun clearError() {
        _errorMessage.value = null
    }

    /** Only while signed out: a session belongs to the server that issued it. */
    fun setServerAddress(text: String): Boolean {
        if (isActive) return false
        val url = normalizedServerUrl(text) ?: return false
        if (url != _serverUrl.value) {
            _serverUrl.value = url
            settings.putString(KEY_SERVER, url)
            if (_state.value == SeerrAuthState.Expired) _state.value = SeerrAuthState.NotConfigured
        }
        return true
    }

    // MARK: Launch

    /** At launch: a stored cookie is checked with the server. An unreachable server keeps the last account. */
    suspend fun restore() {
        // No account: stay off the network entirely.
        val cookie = cookies.get(cookieKey(_serverUrl.value)) ?: return
        refreshPublicSettings()
        val client = clientFactory(_serverUrl.value, cookie)
        try {
            signedIn(client.currentUser(), cookie, client)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SeerrError.Unauthorized) {
            expire()
        } catch (_: Exception) {
            val id = settings.getInt(KEY_USER_ID) ?: return
            this.client = client
            _state.value =
                SeerrAuthState.SignedIn(
                    SeerrUser(id = id, displayName = settings.getString(KEY_USER_NAME), permissions = settings.getInt(KEY_PERMISSIONS)),
                )
        }
    }

    suspend fun refreshPublicSettings() {
        val public =
            try {
                clientFactory(_serverUrl.value, null).publicSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        public.applicationTitle?.takeIf { it.isNotEmpty() }?.let {
            _applicationTitle.value = it
            settings.putString(KEY_TITLE, it)
        }
        partialRequestsEnabled = public.partialRequestsEnabled ?: true
        localLoginEnabled = public.localLogin ?: true
    }

    // MARK: Sign in

    suspend fun signInLocal(
        email: String,
        password: String,
    ) {
        _errorMessage.value = null
        val previous = _state.value
        _state.value = SeerrAuthState.SigningIn
        val client = clientFactory(_serverUrl.value, null)
        try {
            val result = client.signInLocal(email.trim(), password)
            signedIn(result.user, result.cookie, client)
            refreshPublicSettings()
        } catch (e: CancellationException) {
            _state.value = if (previous == SeerrAuthState.Expired) SeerrAuthState.Expired else SeerrAuthState.NotConfigured
            throw e
        } catch (e: Exception) {
            _state.value = if (previous == SeerrAuthState.Expired) SeerrAuthState.Expired else SeerrAuthState.NotConfigured
            _errorMessage.value = e.message
        }
    }

    /** Step 1 of Plex sign-in: a PIN (strong for the web page the phone opens). */
    suspend fun beginPlexSignIn(strong: Boolean = true): PlexPin? {
        _errorMessage.value = null
        return try {
            plexFactory(plexClientIdentifier).createPin(strong).also { _state.value = SeerrAuthState.AwaitingPlex(it.code) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errorMessage.value = "Couldn't start Plex sign-in. ${e.message}"
            null
        }
    }

    /**
     * Step 2: poll until the person links the PIN, then trade the Plex token
     * for a Seerr session. Ends on cancel, on expiry (15 minutes), or on sign-in.
     */
    suspend fun completePlexSignIn(pin: PlexPin) {
        val waiting = SeerrAuthState.AwaitingPlex(pin.code)
        val deadline = clock() + PLEX_TIMEOUT_MILLIS
        val plex = plexFactory(plexClientIdentifier)
        while (_state.value == waiting && clock() < deadline) {
            coroutineContext.ensureActive()
            val token =
                try {
                    plex.checkPin(pin.id).authToken
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (!token.isNullOrEmpty()) {
                if (_state.value == waiting) exchangePlexToken(token)
                return
            }
            if (pollIntervalMillis > 0) delay(pollIntervalMillis) else yield()
        }
        if (_state.value == waiting) {
            _state.value = SeerrAuthState.NotConfigured
            _errorMessage.value = "The Plex code expired. Try again."
        }
    }

    fun cancelPlexSignIn() {
        if (_state.value is SeerrAuthState.AwaitingPlex) _state.value = SeerrAuthState.NotConfigured
    }

    fun plexWebSignInUrl(pin: PlexPin): String = PlexPinClient.webSignInUrl(plexClientIdentifier, pin.code, PLEX_FORWARD_URL)

    private suspend fun exchangePlexToken(token: String) {
        _state.value = SeerrAuthState.SigningIn
        val client = clientFactory(_serverUrl.value, null)
        try {
            val result = client.signInWithPlex(token)
            signedIn(result.user, result.cookie, client)
            refreshPublicSettings()
        } catch (e: CancellationException) {
            _state.value = SeerrAuthState.NotConfigured
            throw e
        } catch (e: Exception) {
            _state.value = SeerrAuthState.NotConfigured
            _errorMessage.value =
                if (e ==
                    SeerrError.InvalidCredentials
                ) {
                    "That Plex account doesn't have access to ${_applicationTitle.value}."
                } else {
                    e.message
                }
        }
    }

    /** Stable per install, so plex.tv lists one "Sashimi" device, not one per sign-in. */
    private val plexClientIdentifier: String
        get() =
            settings.getString(KEY_PLEX_CLIENT_ID) ?: UUID.randomUUID().toString().also { settings.putString(KEY_PLEX_CLIENT_ID, it) }

    // MARK: Sign out and expiry

    suspend fun signOut() {
        client?.logout()
        forget()
        _state.value = SeerrAuthState.NotConfigured
    }

    /**
     * Request screens hand their errors here. A 401/403 is checked with
     * `/auth/me` before it counts as a sign-out: Seerr also answers 401 for
     * things like cancelling a request that's no longer pending.
     */
    suspend fun handle(error: Throwable) {
        if (error != SeerrError.Unauthorized) return
        val client = client ?: return
        try {
            client.currentUser()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SeerrError.Unauthorized) {
            expire()
        } catch (_: Exception) {
            // Unreachable: not evidence of a sign-out.
        }
    }

    fun requestsChanged() {
        _requestsRevision.update { it + 1 }
    }

    private fun signedIn(
        user: SeerrUser,
        cookie: String,
        client: SeerrClient,
    ) {
        cookies.save(cookie, cookieKey(_serverUrl.value))
        settings.putInt(KEY_USER_ID, user.id)
        settings.putString(KEY_USER_NAME, user.name)
        settings.putInt(KEY_PERMISSIONS, user.permissions)
        this.client = client
        _errorMessage.value = null
        _state.value = SeerrAuthState.SignedIn(user)
    }

    private fun expire() {
        forget()
        _state.value = SeerrAuthState.Expired
    }

    private fun forget() {
        cookies.delete(cookieKey(_serverUrl.value))
        settings.putInt(KEY_USER_ID, null)
        settings.putString(KEY_USER_NAME, null)
        settings.putInt(KEY_PERMISSIONS, null)
        client = null
    }

    companion object {
        const val DEFAULT_SERVER = "https://example.com"
        const val PLEX_FORWARD_URL = "sashimi://plex-auth"
        private const val PLEX_TIMEOUT_MILLIS = 15 * 60 * 1000L

        const val KEY_SERVER = "seerr.serverURL"
        const val KEY_USER_ID = "seerr.user.id"
        const val KEY_USER_NAME = "seerr.user.name"
        const val KEY_PERMISSIONS = "seerr.user.permissions"
        const val KEY_TITLE = "seerr.applicationTitle"
        const val KEY_PLEX_CLIENT_ID = "seerr.plexClientIdentifier"

        /** "example.com" to https://example.com; trailing slashes dropped. Only http and https. */
        fun normalizedServerUrl(text: String): String? {
            var trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.contains("://")) trimmed = "https://$trimmed"
            trimmed = trimmed.trimEnd('/')
            val scheme = trimmed.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host =
                trimmed
                    .substringAfter("://")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringAfterLast('@')
                    .substringBefore(':')
            if (host.isEmpty() || host.any { it.isWhitespace() }) return null
            return trimmed
        }

        /** The secure-store key for one server's cookie, so a second server never receives this one's session. */
        fun cookieKey(url: String): String = "seerr.session.${url.lowercase()}"
    }
}
