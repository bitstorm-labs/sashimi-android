package dev.bitstorm.sashimi.core.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Plex's PIN sign-in, shared by Requests (Seerr) and the Watchlist's Connect
 * Plex. A strong PIN goes with Plex's own sign-in page ([webSignInUrl]); a
 * short one is the plex.tv/link code a TV would show. The client id lives
 * under [SeerrSession.KEY_PLEX_CLIENT_ID], so plex.tv lists one "Sashimi"
 * device for both. The token is returned, never stored.
 */
class PlexPinSignIn(
    private val settings: SeerrSettingsStore,
    private val plexFactory: (clientId: String) -> PlexPinClient,
    private val pollIntervalMillis: Long = 2_000,
    private val timeoutMillis: Long = 15 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Stable per install. */
    val clientIdentifier: String
        get() =
            settings.getString(SeerrSession.KEY_PLEX_CLIENT_ID)
                ?: UUID.randomUUID().toString().also { settings.putString(SeerrSession.KEY_PLEX_CLIENT_ID, it) }

    suspend fun begin(strong: Boolean): PlexPin = plexFactory(clientIdentifier).createPin(strong)

    fun webSignInUrl(pin: PlexPin): String = PlexPinClient.webSignInUrl(clientIdentifier, pin.code)

    /**
     * Polls until the PIN is linked. Null when [stillWaiting] turns false or the
     * PIN times out. A failed poll is retried: plex.tv blipping isn't the person giving up.
     */
    suspend fun awaitToken(
        pin: PlexPin,
        stillWaiting: () -> Boolean,
    ): String? {
        val deadline = clock() + timeoutMillis
        val plex = plexFactory(clientIdentifier)
        while (stillWaiting() && clock() < deadline) {
            coroutineContext.ensureActive()
            val token =
                try {
                    plex.checkPin(pin.id).authToken
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (!token.isNullOrEmpty()) return token
            if (pollIntervalMillis > 0) delay(pollIntervalMillis) else yield()
        }
        return null
    }
}
