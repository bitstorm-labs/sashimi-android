package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.seerr.PlexPin
import dev.bitstorm.sashimi.core.seerr.PlexPinSignIn
import dev.bitstorm.sashimi.core.util.runCatchingCancellable

/** What the Watchlist's Connect Plex came to. */
sealed interface ConnectPlexOutcome {
    data object Linked : ConnectPlexOutcome

    /** Another Plex account is linked: the app-wide prompt asks whether to switch. */
    data object Conflict : ConnectPlexOutcome

    /** The dialog went away before Plex answered. Nothing to show. */
    data object Abandoned : ConnectPlexOutcome

    data object CouldNotStart : ConnectPlexOutcome

    data object Expired : ConnectPlexOutcome

    data object Rejected : ConnectPlexOutcome

    data object PlexUnreachable : ConnectPlexOutcome

    data class Failed(
        val error: Throwable,
    ) : ConnectPlexOutcome
}

/**
 * Connect (or Reconnect) Plex for the Watchlist: a strong PIN, Plex's own
 * sign-in page, then the PIN is polled and its token handed to the plugin.
 * Needs nothing from Requests: no Seerr address and no Seerr session.
 */
class WatchlistConnectPlex(
    private val signIn: PlexPinSignIn,
    private val linker: WatchlistPlexLinker,
) {
    /**
     * [resume] is a PIN kept from an earlier run (the activity or process died
     * behind Plex's page): it is polled again, and no new PIN is made.
     * [onPin] gets a new PIN to keep and open Plex's page for ([PlexPinSignIn.webSignInUrl]).
     * [onToken] runs once Plex answered, before the link (the app comes back to the front).
     */
    suspend fun run(
        resume: PlexPin?,
        onPin: (PlexPin) -> Unit,
        onToken: () -> Unit,
        stillWaiting: () -> Boolean,
    ): ConnectPlexOutcome {
        // A PIN kept from before the app was killed behind Plex's page: the person
        // may already have signed in there, so poll it rather than starting over.
        val pin =
            resume ?: runCatchingCancellable { signIn.begin(strong = true) }
                .getOrElse { return ConnectPlexOutcome.CouldNotStart }
                .also(onPin)
        val token = signIn.awaitToken(pin, stillWaiting)
        if (!stillWaiting()) return ConnectPlexOutcome.Abandoned
        if (token == null) return ConnectPlexOutcome.Expired
        onToken()
        val result = runCatchingCancellable { linker.connect(token) }.getOrElse { return ConnectPlexOutcome.Failed(it) }
        return when (result) {
            is PlexLinkResult.Linked -> ConnectPlexOutcome.Linked
            PlexLinkResult.Rejected -> ConnectPlexOutcome.Rejected
            PlexLinkResult.PlexUnreachable -> ConnectPlexOutcome.PlexUnreachable
            is PlexLinkResult.ConflictsWith -> ConnectPlexOutcome.Conflict
        }
    }
}
