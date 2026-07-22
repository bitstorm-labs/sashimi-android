package dev.bitstorm.sashimi.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.PersonInfo
import dev.bitstorm.sashimi.core.model.cleanedYouTubeTitle
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.components.isYouTube
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiBackground
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiLink
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.Formatting
import dev.bitstorm.sashimi.ui.util.ImageUrls
import kotlinx.coroutines.launch

private val WatchedGreen = Color(red = 0.29f, green = 0.73f, blue = 0.47f)

/**
 * One adaptive detail screen covering both iOS layouts (an explicit improvement
 * over the two drifting Swift copies PhoneDetailView + MobileDetailView):
 * compact = stacked phone layout, expanded = tablet two-column (backdrop right,
 * content left). All sections are shared; only the arrangement differs by width.
 *
 * Play / Resume / Start Over launch the Media3 player; Trailer resolves the first
 * local trailer and plays it from the beginning. Shuffle navigates to a random
 * episode's detail.
 */
@Composable
fun DetailScreen(
    itemId: String,
    libraryName: String?,
    isCompact: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (itemId: String, libraryName: String?) -> Unit,
    onPlay: (playItemId: String, startFromBeginning: Boolean) -> Unit,
    onPlayTrailer: (trailerItemId: String) -> Unit,
) {
    val vm: DetailViewModel = viewModel(key = "detail-$itemId", factory = DetailViewModel.Factory(itemId))
    val state by vm.state.collectAsStateWithLifecycle()
    val isOnline by ServiceLocator.networkMonitor.isOnline.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showFileInfo by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    androidx.compose.runtime.LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }
    // Refresh progress/watched state when returning from the player (skip the
    // first RESUME, which coincides with the initial load in init).
    val firstResume = remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (firstResume.value) {
            firstResume.value = false
        } else {
            vm.reload()
        }
        onPauseOrDispose {}
    }

    val item = state.item
    val onPlayMain: (Boolean) -> Unit = { fromBeginning ->
        vm.playTargetId()?.let { onPlay(it, fromBeginning) }
    }
    val onTrailer: () -> Unit = {
        scope.launch { vm.firstLocalTrailerId()?.let { onPlayTrailer(it) } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackTopBar(title = "", onBack = onBack) {
                // The overflow actions (Favorite/File Info/Refresh/Delete) all hit
                // the server, so hide the menu entirely when offline (M4 loose end:
                // they silently failed before).
                if (item != null && isOnline) {
                    DetailOverflowMenu(
                        isFavorite = item.userData?.isFavorite == true,
                        onToggleFavorite = vm::toggleFavorite,
                        onFileInfo = { showFileInfo = true },
                        onRefresh = vm::refreshMetadata,
                        onDelete = { showDeleteConfirm = true },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                item == null && state.isLoading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                item == null ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Could not load item.", color = SashimiTextSecondary)
                    }
                isCompact ->
                    CompactLayout(state, item, libraryName, vm, onOpenDetail, onPlayMain, onTrailer)
                else ->
                    ExpandedLayout(state, item, libraryName, vm, onOpenDetail, onPlayMain, onTrailer)
            }
        }
    }

    if (showFileInfo && item != null) {
        AlertDialog(
            onDismissRequest = { showFileInfo = false },
            confirmButton = { TextButton(onClick = { showFileInfo = false }) { Text("OK") } },
            title = { Text("File Info") },
            text = { Text(state.mediaInfo?.path ?: item.path ?: "Path not available") },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Item") },
            text = { Text("Are you sure you want to delete this item? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteItem()
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CompactLayout(
    state: DetailUiState,
    item: BaseItemDto,
    libraryName: String?,
    vm: DetailViewModel,
    onOpenDetail: (String, String?) -> Unit,
    onPlay: (Boolean) -> Unit,
    onTrailer: () -> Unit,
) {
    val yt = isYouTube(libraryName, item.path)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(SashimiCard)) {
            AsyncImage(
                model = ImageUrls.detailBackdrop(item, yt && item.type == ItemType.SERIES),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, SashimiBackground)),
                ),
            )
        }
        DetailContent(state, item, libraryName, vm, onOpenDetail, onPlay, onTrailer, Modifier.padding(16.dp))
    }
}

@Composable
private fun ExpandedLayout(
    state: DetailUiState,
    item: BaseItemDto,
    libraryName: String?,
    vm: DetailViewModel,
    onOpenDetail: (String, String?) -> Unit,
    onPlay: (Boolean) -> Unit,
    onTrailer: () -> Unit,
) {
    val yt = isYouTube(libraryName, item.path)
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageUrls.detailBackdrop(item, yt && item.type == ItemType.SERIES),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.TopEnd,
            modifier = Modifier.fillMaxWidth(0.55f).align(Alignment.TopEnd),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0.0f to SashimiBackground,
                    0.5f to SashimiBackground,
                    1.0f to Color.Transparent,
                ),
            ),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.6f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
        ) {
            DetailContent(state, item, libraryName, vm, onOpenDetail, onPlay, onTrailer)
        }
    }
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    item: BaseItemDto,
    libraryName: String?,
    vm: DetailViewModel,
    onOpenDetail: (String, String?) -> Unit,
    onPlay: (Boolean) -> Unit,
    onTrailer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yt = isYouTube(libraryName, item.path)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TitleBlock(item, yt)
        MetadataRow(item)
        RatingsAndMedia(state, item)
        if (item.type == ItemType.MOVIE) GenresCert(item)
        ActionButtons(state, item, vm, onOpenDetail, libraryName, onPlay, onTrailer)
        OverviewSection(item)
        if (state.isSeries || state.isEpisode) SeasonsSection(state, vm, onOpenDetail, libraryName)
        CastSection(item)
    }
}

@Composable
private fun TitleBlock(
    item: BaseItemDto,
    yt: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (item.type) {
            ItemType.EPISODE -> {
                val seriesName = item.seriesName
                if (seriesName != null) {
                    Text(
                        if (yt) seriesName.cleanedYouTubeTitle() else seriesName,
                        color = SashimiTextSecondary,
                        fontSize = 13.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val s = item.parentIndexNumber
                    val e = item.indexNumber
                    if (!yt && s != null && e != null) {
                        Text("S$s:E$e", color = SashimiTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("•", color = SashimiTextTertiary, fontSize = 22.sp)
                    }
                    Text(
                        item.name,
                        color = SashimiTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            else ->
                LogoOrTitle(item, yt)
        }
    }
}

@Composable
private fun LogoOrTitle(
    item: BaseItemDto,
    yt: Boolean,
) {
    val title = if (yt && item.type == ItemType.SERIES) item.name.cleanedYouTubeTitle() else item.name
    // Server logo when present; fall back to bold title (Coil error -> nothing,
    // so we always render the title beneath at a smaller weight? Keep it simple:
    // show the title text — logos are a nice-to-have, the text is the guarantee).
    Text(title, color = SashimiTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MetadataRow(item: BaseItemDto) {
    val parts = mutableListOf<String>()
    Formatting.premiereDateLong(item.premiereDate)?.let { parts.add(it) }
        ?: item.productionYear?.let { parts.add(it.toString()) }
    item.runTimeTicks?.let { parts.add(Formatting.runtime(it)) }
    val endsAt = item.runTimeTicks?.let { Formatting.endsAt(it) }

    if (parts.isNotEmpty() || endsAt != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" • "), color = SashimiTextSecondary, fontSize = 13.sp)
            }
            if (endsAt != null) {
                if (parts.isNotEmpty()) Text("•", color = SashimiTextSecondary, fontSize = 13.sp)
                Text(endsAt, color = SashimiAccent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RatingsAndMedia(
    state: DetailUiState,
    item: BaseItemDto,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val community = item.communityRating ?: state.seriesCommunityRating
        val critic = item.criticRating ?: state.seriesCriticRating
        if (community != null && community > 0) {
            Text("TMDB ${"%.1f".format(community)}", color = SashimiTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (critic != null) {
            Text("🍅 $critic%", color = SashimiTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (!state.isSeries) {
            state.mediaInfo?.let { info ->
                info.videoResolution?.let { MediaBadge(it) }
                info.videoCodec?.let { MediaBadge(Formatting.codec(it)) }
                val ac = info.audioCodec
                val ch = info.audioChannels
                if (ac != null && ch != null) MediaBadge("${Formatting.codec(ac)} ${Formatting.channels(ch)}")
            }
        }
    }
}

@Composable
private fun MediaBadge(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(SashimiCard)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun GenresCert(item: BaseItemDto) {
    val cert = item.officialRating
    val genres = item.genres?.take(3)?.joinToString(" • ")
    if (cert != null || !genres.isNullOrEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cert != null) {
                Text(
                    cert,
                    color = SashimiTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(SashimiCard).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (!genres.isNullOrEmpty()) {
                Text(genres, color = SashimiTextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ActionButtons(
    state: DetailUiState,
    item: BaseItemDto,
    vm: DetailViewModel,
    onOpenDetail: (String, String?) -> Unit,
    libraryName: String?,
    onPlay: (Boolean) -> Unit,
    onTrailer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playLabel = playButtonLabel(state, item)
        Button(onClick = { onPlay(false) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(playLabel, modifier = Modifier.padding(start = 4.dp))
        }

        if (state.hasProgress || (state.isSeries && state.nextEpisode?.userData?.playbackPositionTicks?.let { it > 0 } == true)) {
            FilledTonalButton(onClick = { onPlay(true) }) {
                Icon(Icons.Filled.Replay, contentDescription = "Start Over", modifier = Modifier.size(18.dp))
            }
        }

        if (state.isSeries) {
            FilledTonalButton(onClick = {
                scope.launch { vm.randomEpisode()?.let { onOpenDetail(it.id, libraryName) } }
            }) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(18.dp))
            }
        }

        if ((item.localTrailerCount ?: 0) > 0) {
            FilledTonalButton(onClick = onTrailer) {
                Icon(Icons.Filled.Movie, contentDescription = "Trailer", modifier = Modifier.size(18.dp))
            }
        }

        // Watched state is a server call — only offer the toggle when online (M4
        // loose end: it silently failed offline).
        val online by ServiceLocator.networkMonitor.isOnline.collectAsStateWithLifecycle()
        if (online) {
            FilledTonalButton(onClick = vm::toggleWatched) {
                Icon(
                    if (state.isWatched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = "Watched",
                    tint = if (state.isWatched) SashimiAccent else SashimiTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Single-item download (movies + episodes). Series use the bulk season menu.
        if (item.type == ItemType.MOVIE || item.type == ItemType.EPISODE) {
            dev.bitstorm.sashimi.ui.downloads.DownloadButton(item = item)
        }
    }
}

private fun playButtonLabel(
    state: DetailUiState,
    item: BaseItemDto,
): String =
    when {
        state.isSeries -> {
            val next = state.nextEpisode
            if (next != null) {
                val progress = (next.userData?.playbackPositionTicks ?: 0) > 0
                val verb = if (progress) "Resume" else "Play"
                val s = next.parentIndexNumber
                val e = next.indexNumber
                if (s != null && e != null) "$verb S$s:E$e" else verb
            } else {
                "Play"
            }
        }
        else -> if (state.hasProgress) "Resume" else "Play"
    }

@Composable
private fun OverviewSection(item: BaseItemDto) {
    val clean = Formatting.stripUrls(item.overview ?: "")
    if (clean.isEmpty()) return
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            clean,
            color = SashimiTextSecondary,
            fontSize = 15.sp,
            maxLines = if (expanded) 50 else 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (expanded) "Show Less" else "Show More", color = SashimiLink, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SeasonsSection(
    state: DetailUiState,
    vm: DetailViewModel,
    onOpenDetail: (String, String?) -> Unit,
    libraryName: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.seasons.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.seasons.forEach { season ->
                    val selected = season.id == state.selectedSeasonId
                    AssistChip(
                        onClick = { vm.selectSeason(season.id) },
                        label = { Text(season.name) },
                        colors =
                            androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) SashimiAccent else SashimiCard,
                                labelColor = if (selected) Color.Black else Color.White,
                            ),
                    )
                }
            }
        }
        if (state.isLoadingEpisodes) {
            Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
        } else if (state.episodes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.isEpisode) "More Episodes" else "Episodes",
                    color = SashimiTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                val online by ServiceLocator.networkMonitor.isOnline.collectAsStateWithLifecycle()
                if (state.isSeries && online) {
                    SeasonDownloadMenu(episodes = state.episodes)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.episodes.forEach { episode ->
                    EpisodeRow(
                        episode = episode,
                        isCurrent = episode.id == state.currentEpisodeId,
                        onClick = { onOpenDetail(episode.id, libraryName) },
                    )
                }
            }
        }
    }
}

/**
 * Bulk season download menu (All / Unwatched / Custom N), ported from the Swift
 * detail download menu. "Custom" downloads the first N unwatched episodes. The
 * quality dialog gates Original off the first episode as a season proxy.
 */
@Composable
private fun SeasonDownloadMenu(episodes: List<BaseItemDto>) {
    var menuOpen by remember { mutableStateOf(false) }
    var pendingEpisodes by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var customCount by remember { mutableStateOf("") }

    val unwatched = episodes.filter { it.userData?.played != true }

    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.Download, contentDescription = "Download season", tint = SashimiAccent)
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(text = { Text("All Episodes") }, onClick = {
            menuOpen = false
            pendingEpisodes = episodes
        })
        DropdownMenuItem(text = { Text("Unwatched Only") }, onClick = {
            menuOpen = false
            if (unwatched.isNotEmpty()) pendingEpisodes = unwatched
        })
        DropdownMenuItem(text = { Text("Custom…") }, onClick = {
            menuOpen = false
            if (unwatched.isNotEmpty()) showCustom = true
        })
    }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("Download Unwatched Episodes") },
            text = {
                Column {
                    Text("How many unwatched episodes would you like to download?", color = SashimiTextSecondary)
                    androidx.compose.material3.OutlinedTextField(
                        value = customCount,
                        onValueChange = { customCount = it.filter(Char::isDigit) },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = customCount.toIntOrNull() ?: 0
                    showCustom = false
                    customCount = ""
                    if (n > 0) pendingEpisodes = unwatched.take(n)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } },
        )
    }

    pendingEpisodes?.let { eps ->
        if (eps.isNotEmpty()) {
            dev.bitstorm.sashimi.ui.downloads.QualityDialog(
                item = eps.first(),
                seasonProxyItemId = eps.first().id,
                onDismiss = { pendingEpisodes = null },
                onPick = { quality ->
                    pendingEpisodes = null
                    ServiceLocator.downloadManager.downloadSeason(eps, quality)
                },
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: BaseItemDto,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isCurrent) SashimiCard else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(120.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SashimiCard),
        ) {
            AsyncImage(
                model = ImageUrls.primary(episode.id, 400),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (episode.userData?.played == true) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Watched",
                    tint = WatchedGreen,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp),
                )
            }
            if (episode.progressPercent > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(episode.progressPercent.toFloat().coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(SashimiAccent),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            val epNum = episode.indexNumber
            val aired = Formatting.shortAirDate(episode.premiereDate)
            if (epNum != null) {
                Row {
                    Text("E$epNum", color = SashimiAccent, fontSize = 12.sp)
                    if (aired != null) Text(" • $aired", color = SashimiTextTertiary, fontSize = 12.sp)
                }
            }
            Text(
                episode.name,
                color = SashimiTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.runTimeTicks?.let {
                Text(Formatting.runtime(it), color = SashimiTextTertiary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CastSection(item: BaseItemDto) {
    val cast = item.people?.filter { it.type == "Actor" }?.take(15).orEmpty()
    if (cast.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cast", color = SashimiTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            cast.forEach { CastCard(it) }
        }
    }
}

@Composable
private fun CastCard(person: PersonInfo) {
    Column(modifier = Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(70.dp).clip(CircleShape).background(SashimiCard)) {
            if (person.primaryImageTag != null) {
                AsyncImage(
                    model = ImageUrls.person(person.id),
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            person.name,
            color = SashimiTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        person.role?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = SashimiTextTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailOverflowMenu(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onFileInfo: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
            leadingIcon = { Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null) },
            onClick = {
                open = false
                onToggleFavorite()
            },
        )
        DropdownMenuItem(
            text = { Text("File Info") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = {
                open = false
                onFileInfo()
            },
        )
        DropdownMenuItem(
            text = { Text("Refresh Metadata") },
            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            onClick = {
                open = false
                onRefresh()
            },
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = {
                open = false
                onDelete()
            },
        )
    }
}
