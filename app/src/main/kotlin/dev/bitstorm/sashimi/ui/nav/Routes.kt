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
 * the full item on entry (the Swift cast-lesson refresh). Deep links
 * `sashimi://item/{itemId}` and `sashimi://play/{itemId}` both resolve here
 * (play-to-detail is a TODO until M3 owns playback).
 */
@Serializable
data class DetailRoute(
    val itemId: String,
    val libraryName: String? = null,
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
)
