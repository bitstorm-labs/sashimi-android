package dev.bitstorm.sashimi.core.browse

/**
 * Sort field for library browsing. `wire` is the Jellyfin SortBy value; `label`
 * is the menu title. Port of MobileLibraryBrowseView.LibrarySort.
 */
enum class LibrarySort(
    val wire: String,
    val label: String,
) {
    NAME("SortName", "Name"),
    DATE_ADDED("DateCreated", "Date Added"),
    RELEASE_DATE("PremiereDate", "Release Date"),
    RATING("CommunityRating", "Rating"),
    RUNTIME("Runtime", "Runtime"),
}

/**
 * Watched / favorites filter. [isPlayed] / [isFavorite] map to the getItems
 * query params (null = don't constrain). Port of the Swift LibraryFilter.
 */
enum class LibraryFilter(
    val label: String,
) {
    ALL("All"),
    UNWATCHED("Unwatched"),
    WATCHED("Watched"),
    FAVORITES("Favorites"),
    ;

    val isPlayed: Boolean?
        get() =
            when (this) {
                UNWATCHED -> false
                WATCHED -> true
                else -> null
            }

    val isFavorite: Boolean?
        get() = if (this == FAVORITES) true else null
}

/** Sort direction, mapped to the Jellyfin SortOrder value. */
enum class SortDirection(
    val wire: String,
) {
    ASCENDING("Ascending"),
    DESCENDING("Descending"),
    ;

    fun toggled(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}
