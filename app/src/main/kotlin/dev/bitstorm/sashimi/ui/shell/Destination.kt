package dev.bitstorm.sashimi.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bitstorm.sashimi.ui.nav.DownloadsRoute
import dev.bitstorm.sashimi.ui.nav.HomeRoute
import dev.bitstorm.sashimi.ui.nav.LibrariesRoute
import dev.bitstorm.sashimi.ui.nav.SearchRoute
import dev.bitstorm.sashimi.ui.nav.SettingsRoute

/** The five top-level destinations (iPhone parity: Home/Libraries/Search/Downloads/Settings). */
enum class Destination(
    val label: String,
    val icon: ImageVector,
    val route: Any,
) {
    HOME("Home", Icons.Filled.Home, HomeRoute),
    LIBRARIES("Libraries", Icons.AutoMirrored.Filled.List, LibrariesRoute),
    SEARCH("Search", Icons.Filled.Search, SearchRoute),
    DOWNLOADS("Downloads", Icons.Filled.Download, DownloadsRoute),
    SETTINGS("Settings", Icons.Filled.Settings, SettingsRoute),
}
