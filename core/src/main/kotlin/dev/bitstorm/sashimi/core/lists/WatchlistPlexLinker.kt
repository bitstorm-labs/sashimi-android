package dev.bitstorm.sashimi.core.lists

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A question the app asks about the Watchlist's Plex link. Carries only what it shows. */
sealed interface LinkPrompt {
    /** The Watchlist syncs with another Plex account; switch to [username]? */
    data class Replace(
        val username: String,
    ) : LinkPrompt
}

/**
 * Links the Watchlist to Plex: from the Watchlist's own Connect Plex, and
 * automatically when Requests sign-in gets a Plex token. The token is held in
 * memory only while the person answers a [LinkPrompt.Replace], then dropped.
 */
class WatchlistPlexLinker(
    private val service: WatchlistService,
    private val scope: CoroutineScope,
) {
    private val _prompt = MutableStateFlow<LinkPrompt?>(null)
    val prompt: StateFlow<LinkPrompt?> = _prompt.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /**
     * Fires each time the Watchlist's Plex link changes (linked, switched or
     * disconnected), so the Watchlist screen and Settings re-read it.
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    @Volatile private var heldToken: String? = null

    /** The Jellyfin server and user the held token was offered for. */
    @Volatile private var heldFor: String? = null

    /** Connect / Reconnect Plex from the Watchlist. Errors reach the caller, which shows them. */
    suspend fun connect(token: String): PlexLinkResult {
        val result = service.linkPlex(token, replace = false)
        if (result is PlexLinkResult.ConflictsWith) ask(token, result)
        if (result is PlexLinkResult.Linked) _changes.tryEmit(Unit)
        return result
    }

    /** Disconnect Plex. Errors reach the caller, which shows them. */
    suspend fun disconnect() {
        service.unlinkPlex()
        _changes.tryEmit(Unit)
    }

    /**
     * [autoLink] without holding up the caller: Requests sign-in finishes (and
     * Plex's page closes) while the Watchlist link runs on its own.
     */
    fun autoLinkInBackground(token: String): Job = scope.launch { autoLink(token) }

    /**
     * Requests sign-in just got [token]. Never fails and never speaks unless
     * something changed: no plugin, an error, or a rejected token are all silent.
     */
    internal suspend fun autoLink(token: String) {
        try {
            val view = service.view() ?: return
            when (val result = service.linkPlex(token, replace = false)) {
                is PlexLinkResult.Linked -> {
                    if (view.plex == null) _notices.tryEmit(LINKED_NOTICE)
                    // Even a quiet relink of the same account can clear "Plex stopped syncing".
                    _changes.tryEmit(Unit)
                }
                is PlexLinkResult.ConflictsWith -> ask(token, result)
                PlexLinkResult.Rejected, PlexLinkResult.PlexUnreachable -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Jellyfin unreachable or signed out: Requests sign-in carries on, the token is dropped.
        }
    }

    /** The answer to [LinkPrompt.Replace]. Either way the held token is gone afterwards. Returns the relink, if any. */
    fun answer(replace: Boolean): Job? {
        val token = heldToken.takeIf { heldFor == service.sessionKey() }
        drop()
        if (!replace || token == null) return null
        return scope.launch {
            try {
                if (service.linkPlex(token, replace = true) is PlexLinkResult.Linked) {
                    _notices.tryEmit(LINKED_NOTICE)
                    _changes.tryEmit(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The Watchlist screen still offers Connect Plex.
            }
        }
    }

    /**
     * False (and the question and token dropped) when the Jellyfin session
     * changed since it was asked: another person must never be offered, or
     * linked with, someone else's Plex token.
     */
    fun promptStillValid(): Boolean {
        if (_prompt.value == null) return false
        if (heldFor == service.sessionKey()) return true
        drop()
        return false
    }

    private fun drop() {
        heldToken = null
        heldFor = null
        _prompt.value = null
    }

    private fun ask(
        token: String,
        conflict: PlexLinkResult.ConflictsWith,
    ) {
        heldToken = token
        heldFor = service.sessionKey()
        _prompt.value = LinkPrompt.Replace(conflict.newUsername.ifBlank { conflict.username })
    }

    override fun toString(): String = "WatchlistPlexLinker(prompt=${_prompt.value})"

    companion object {
        const val LINKED_NOTICE = "Your Watchlist now syncs with Plex"
    }
}
