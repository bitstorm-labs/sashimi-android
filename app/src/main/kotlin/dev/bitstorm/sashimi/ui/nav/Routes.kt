package dev.bitstorm.sashimi.ui.nav

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes (navigation-compose 2.8 @Serializable routes). The
 * five tab roots plus the two pushed destinations (library browse + detail).
 * Detail is reachable from every surface and via the sashimi:// deep links.
 */
@Serializable
object HomeRoute

@Serializable
object LibrariesRoute

@Serializable
object SearchRoute

@Serializable
object DownloadsRoute

@Serializable
object SettingsRoute

/** Home row order + visibility editor, pushed from Settings. */
@Serializable
object HomeRowOrderRoute

@Serializable
data class LibraryBrowseRoute(
    val libraryId: String,
    val libraryName: String,
    val collectionType: String? = null,
)

/** "See All" grid for a Home Recently Added row (shown when a row has >6 items). */
@Serializable
data class RecentlyAddedRoute(
    val libraryId: String,
    val libraryName: String,
    val collectionType: String? = null,
)

/**
 * Item detail. Carries only the id + optional library name; the screen fetches
 * the full item on entry (the Swift cast-lesson refresh). The
 * `sashimi://item/{itemId}` deep link resolves here; `sashimi://play/{itemId}`
 * resolves straight to [PlayerRoute].
 */
@Serializable
data class DetailRoute(
    val itemId: String,
    val libraryName: String? = null,
    /**
     * Set when the item belongs to a specific saved server rather than whatever
     * server is active (a title opened from a cross-server filmography). The
     * screen then reads through that server's own client and never switches the
     * active server. Null means "the active server", as before.
     */
    val serverId: String? = null,
)

/**
 * A cast or crew member's page: name, image and filmography across every saved
 * server. [originServerId] is the server the tapped cast row came from, the only
 * server on which [personId] is meaningful. [excludeItemId] and
 * [excludeTitleKey] identify the title the user came from, so it is not listed
 * as "something else they're in".
 */
@Serializable
data class PersonRoute(
    val personId: String,
    val name: String,
    val role: String? = null,
    val type: String? = null,
    val primaryImageTag: String? = null,
    val originServerId: String? = null,
    val excludeItemId: String? = null,
    val excludeTitleKey: String? = null,
)

/**
 * Full-screen Media3 player. [startFromBeginning] forces a Start Over (ignore
 * the saved resume position). [trailerItemId], when set, plays that item from 0
 * instead of [itemId] (the local-first Trailer button). The `sashimi://play/{id}`
 * deep link resolves straight here.
 */
@Serializable
data class PlayerRoute(
    val itemId: String,
    val startFromBeginning: Boolean = false,
    val trailerItemId: String? = null,
    /**
     * The saved server the item belongs to, when it was opened from a server
     * other than the active one ([DetailRoute.serverId]). The player then
     * negotiates, streams and reports progress against that server without
     * switching the active one. Null means the active server.
     */
    val serverId: String? = null,
)
