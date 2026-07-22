package dev.bitstorm.sashimi.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.downloads.DownloadedItemEntity
import dev.bitstorm.sashimi.core.downloads.OfflineReconstruction
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

private val OfflineWarning = Color(0xFFFF9800)

/**
 * Offline Home, ported from the Swift `OfflineHomeView`: an offline banner, then
 * Continue Watching (local progress), Movies, and TV Shows sections drawn from
 * the local download store. Tapping a movie/episode plays it locally; a series
 * opens its (offline-reconstructed) detail.
 */
@Composable
fun OfflineHomeScreen(
    onPlay: (itemId: String) -> Unit,
    onOpenSeries: (seriesId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloads by ServiceLocator.downloadManager.downloads.collectAsStateWithLifecycle()

    val continueWatching = OfflineReconstruction.continueWatching(downloads)
    val movies = OfflineReconstruction.movies(downloads)
    val series = OfflineReconstruction.seriesGroups(downloads)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { OfflineBanner() }

        if (continueWatching.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            item { EmptyOffline() }
        }

        if (continueWatching.isNotEmpty()) {
            item {
                Section("Continue Watching") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(continueWatching, key = { it.itemId }) { row ->
                            ContinueCard(row, onClick = { onPlay(row.itemId) })
                        }
                    }
                }
            }
        }

        if (movies.isNotEmpty()) {
            item {
                Section("Movies") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(movies, key = { it.itemId }) { row ->
                            PosterCard(row.itemId, row.name, onClick = { onPlay(row.itemId) })
                        }
                    }
                }
            }
        }

        if (series.isNotEmpty()) {
            item {
                Section("TV Shows") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(series, key = { it.seriesId }) { group ->
                            PosterCard(
                                imageItemId = group.representative.itemId,
                                title = group.seriesName,
                                badge = "${group.episodes.size}",
                                onClick = { onOpenSeries(group.seriesId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
            .background(OfflineWarning.copy(alpha = 0.15f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = OfflineWarning, modifier = Modifier.size(18.dp))
        Text("You're offline. Showing downloaded content.", color = OfflineWarning, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyOffline() {
    Column(
        Modifier.fillMaxWidth().height(240.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No Downloads", color = SashimiTextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Download movies and episodes while online to watch them offline.",
            color = SashimiTextTertiary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = SashimiTextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        content()
    }
}

@Composable
private fun ContinueCard(
    row: DownloadedItemEntity,
    onClick: () -> Unit,
) {
    val remaining = ((row.runTimeTicks ?: 0) - row.localPositionTicks).coerceAtLeast(0) / 600_000_000
    val progress =
        row.runTimeTicks?.takeIf { it > 0 }?.let { (row.localPositionTicks.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    Column(Modifier.width(240.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(8.dp)).background(SashimiCard)) {
            AsyncImage(
                model = OfflineImages.localBackdrop(row.itemId),
                contentDescription = row.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(SashimiAccent),
            )
        }
        Text(row.displayTitle, color = SashimiTextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${remaining}m left", color = SashimiTextTertiary, fontSize = 11.sp)
    }
}

@Composable
private fun PosterCard(
    imageItemId: String,
    title: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(110.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(8.dp)).background(SashimiCard)) {
            AsyncImage(
                model = OfflineImages.localPoster(imageItemId),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier.align(
                            Alignment.TopEnd,
                        ).padding(
                            4.dp,
                        ).clip(RoundedCornerShape(4.dp)).background(SashimiAccent).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, color = SashimiTextPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
