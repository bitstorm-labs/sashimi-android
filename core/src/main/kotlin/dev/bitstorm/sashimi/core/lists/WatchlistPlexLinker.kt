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

    @Volatile private var heldToken: String? = null

    /** Connect / Reconnect Plex from the Watchlist. Errors reach the caller, which shows them. */
    suspend fun connect(token: String): PlexLinkResult {
        val result = service.linkPlex(token, replace = false)
        if (result is PlexLinkResult.ConflictsWith) ask(token, result)
        return result
    }

    /**
     * Requests sign-in just got [token]. Never fails and never speaks unless
     * something changed: no plugin, an error, or a rejected token are all silent.
     */
    suspend fun autoLink(token: String) {
        try {
            val view = service.view() ?: return
            when (val result = service.linkPlex(token, replace = false)) {
                is PlexLinkResult.Linked -> if (view.plex == null) _notices.tryEmit(LINKED_NOTICE)
                is PlexLinkResult.ConflictsWith -> ask(token, result)
                PlexLinkResult.Rejected -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Jellyfin unreachable or signed out: Requests sign-in carries on, the token is dropped.
        }
    }

    /** The answer to [LinkPrompt.Replace]. Either way the held token is gone afterwards. Returns the relink, if any. */
    fun answer(replace: Boolean): Job? {
        val token = heldToken
        heldToken = null
        _prompt.value = null
        if (!replace || token == null) return null
        return scope.launch {
            try {
                if (service.linkPlex(token, replace = true) is PlexLinkResult.Linked) _notices.tryEmit(LINKED_NOTICE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The Watchlist screen still offers Connect Plex.
            }
        }
    }

    private fun ask(
        token: String,
        conflict: PlexLinkResult.ConflictsWith,
    ) {
        heldToken = token
        _prompt.value = LinkPrompt.Replace(conflict.newUsername.ifBlank { conflict.username })
    }

    override fun toString(): String = "WatchlistPlexLinker(prompt=${_prompt.value})"

    companion object {
        const val LINKED_NOTICE = "Your Watchlist now syncs with Plex"
    }
}
