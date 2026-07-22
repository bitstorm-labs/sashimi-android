package dev.bitstorm.sashimi.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The five top-level destinations (iPhone parity: Home/Libraries/Search/Downloads/Settings). */
enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Filled.Home),
    LIBRARIES("Libraries", Icons.AutoMirrored.Filled.List),
    SEARCH("Search", Icons.Filled.Search),
    DOWNLOADS("Downloads", Icons.Filled.Download),
    SETTINGS("Settings", Icons.Filled.Settings),
}
