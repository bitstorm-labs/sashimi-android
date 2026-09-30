package dev.bitstorm.sashimi.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.downloads.DownloadActivity
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.theme.SashimiAccent

/**
 * The Downloads destination's icon, doubling as the app's global download
 * indicator (#69, parity with sashimi-apple#115). There is no app-wide top
 * bar, so the indicator lives on the one destination that is always visible
 * in the bottom bar and the rail.
 *
 * Idle: the plain icon. While anything is queued or downloading: a progress
 * ring around a smaller icon (overall progress, see [DownloadActivity]) and a
 * badge with the active count. Tapping it is tapping the destination, which
 * opens Downloads.
 */
@Composable
fun DownloadsDestinationIcon(
    icon: ImageVector,
    label: String,
) {
    val downloads by ServiceLocator.downloadManager.downloads.collectAsStateWithLifecycle()
    val activity = remember(downloads) { DownloadActivity.of(downloads) }
    if (activity == null) {
        Icon(icon, contentDescription = label)
        return
    }
    val description = "$label, ${activity.activeCount} in progress"
    BadgedBox(badge = { Badge { Text("${activity.activeCount}") } }) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            val progress = activity.progress
            if (progress == null) {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp, color = SashimiAccent)
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.dp,
                    color = SashimiAccent,
                )
            }
            Icon(icon, contentDescription = description, modifier = Modifier.size(14.dp))
        }
    }
}
