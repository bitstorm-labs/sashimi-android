package dev.bitstorm.sashimi.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bitstorm.sashimi.core.home.PrimaryTab
import dev.bitstorm.sashimi.ui.nav.DownloadsRoute
import dev.bitstorm.sashimi.ui.nav.HomeRoute
import dev.bitstorm.sashimi.ui.nav.LibrariesRoute
import dev.bitstorm.sashimi.ui.nav.ListsRoute
import dev.bitstorm.sashimi.ui.nav.SearchRoute
import dev.bitstorm.sashimi.ui.nav.SettingsRoute
import dev.bitstorm.sashimi.ui.nav.WatchlistRoute

/**
 * The top-level destinations (iPhone parity: Home/Libraries/Search/Downloads/Settings,
 * plus Lists for shared lists and the Watchlist, both of which can be hidden
 * from Home & Side Menu). The phone bar's order and filtering are
 * [PrimaryTab.phoneTabs]; each entry here is the look of one [PrimaryTab].
 */
enum class Destination(
    val label: String,
    val icon: ImageVector,
    val route: Any,
) {
    HOME("Home", Icons.Filled.Home, HomeRoute),
    LIBRARIES("Libraries", Icons.AutoMirrored.Filled.List, LibrariesRoute),
    LISTS("Lists", Icons.AutoMirrored.Filled.PlaylistPlay, ListsRoute),
    SEARCH("Search", Icons.Filled.Search, SearchRoute),
    WATCHLIST("Watchlist", Icons.Filled.Bookmark, WatchlistRoute),
    DOWNLOADS("Downloads", Icons.Filled.Download, DownloadsRoute),
    SETTINGS("Settings", Icons.Filled.Settings, SettingsRoute),
    ;

    companion object {
        fun of(tab: PrimaryTab): Destination = valueOf(tab.name)
    }
}
