package dev.bitstorm.sashimi.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.R
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.ui.components.ContextMenuBox
import dev.bitstorm.sashimi.ui.components.ContinueWatchingCard
import dev.bitstorm.sashimi.ui.components.PosterCard
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiLink
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import kotlinx.coroutines.launch

/**
 * Home tab. Configurable rows (Continue Watching + per-library Recently Added),
 * pull-to-refresh, and a title bar whose logo opens the server switcher. Port of
 * PhoneHomeView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: SessionManager,
    onOpenDetail: (itemId: String, libraryName: String?) -> Unit,
    onSeeAll: (libraryId: String, libraryName: String, collectionType: String?) -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory())
    val state by vm.state.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state.continueWatching.isEmpty()) vm.loadContent()
    }

    // Refresh Continue Watching when returning to Home (e.g. after playback exit),
    // skipping the first resume which coincides with the initial load above.
    val firstResume = remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (firstResume.value) {
            firstResume.value = false
        } else {
            vm.loadContent()
        }
        onPauseOrDispose {}
    }

    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                vm.refresh()
                isRefreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            item { HomeTopBar(session = session, onAddServer = onAddServer) }

            if (state.isLoading && state.continueWatching.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(300.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(rows.filter { it.isEnabled }, key = { it.id }) { row ->
                    when (row.kind) {
                        HomeRowConfig.Kind.CONTINUE_WATCHING ->
                            if (state.continueWatching.isNotEmpty()) {
                                ContinueWatchingRow(
                                    items = state.continueWatching,
                                    libraryNames = state.continueWatchingLibraryNames,
                                    onOpenDetail = onOpenDetail,
                                    onRefresh = { vm.loadContent() },
                                )
                            }
                        HomeRowConfig.Kind.LIBRARY ->
                            RecentlyAddedRow(
                                vm = vm,
                                libraryId = row.libraryId ?: return@items,
                                libraryName = row.libraryName ?: "",
                                collectionType = state.libraries.firstOrNull { it.id == row.libraryId }?.collectionType,
                                onOpenDetail = onOpenDetail,
                                onSeeAll = onSeeAll,
                            )
                    }
                }

                if (state.continueWatching.isEmpty() && state.libraries.isEmpty() && !state.isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().height(300.dp), Alignment.Center) {
                            Text("Start watching something to see it here.", color = SashimiTextTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    session: SessionManager,
    onAddServer: () -> Unit,
) {
    val servers by session.servers.collectAsStateWithLifecycle()
    val activeId by session.activeServerId.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            // Logo + wordmark; tapping the whole thing opens the server switcher
            // (port of PhoneHomeView's safeAreaInset header Menu).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuOpen = true }
                        .padding(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.sashimi_logo),
                    contentDescription = "Sashimi",
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Sashimi",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SashimiTextPrimary,
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "Switch server",
                    tint = SashimiTextTertiary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                servers.forEach { server ->
                    DropdownMenuItem(
                        text = { Text(server.name) },
                        leadingIcon = {
                            if (server.id == activeId) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            session.switchServer(server.id)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Add Server…") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onAddServer()
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<dev.bitstorm.sashimi.core.model.BaseItemDto>,
    libraryNames: Map<String, String>,
    onOpenDetail: (String, String?) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Continue Watching")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val libraryName = libraryNames[item.id]
                ContextMenuBox(
                    item = item,
                    onClick = { onOpenDetail(item.id, libraryName) },
                    onAction = onRefresh,
                ) {
                    ContinueWatchingCard(item = item, width = 260.dp, libraryName = libraryName)
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedRow(
    vm: HomeViewModel,
    libraryId: String,
    libraryName: String,
    collectionType: String?,
    onOpenDetail: (String, String?) -> Unit,
    onSeeAll: (String, String, String?) -> Unit,
) {
    val recentlyAdded by vm.recentlyAdded.collectAsStateWithLifecycle()
    val data = recentlyAdded[libraryId]

    // Fallback for rows the up-front prefetch didn't cover (e.g. a newly added
    // library). Idempotent in the ViewModel, so this never refetches a cached row.
    androidx.compose.runtime.LaunchedEffect(libraryId, collectionType) {
        vm.ensureRecentlyAdded(libraryId, libraryName, collectionType)
    }

    // Loaded and empty → hide the row entirely. Null == still loading (placeholders).
    if (data != null && data.items.isEmpty()) return

    val isYouTube = libraryName.contains("youtube", ignoreCase = true)
    val title = "Recently Added $libraryName".let { if (it.endsWith(" - Videos")) it.dropLast(9) else it }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SashimiTextPrimary, modifier = Modifier.weight(1f))
            if (data != null && data.items.size > 6) {
                TextButton(onClick = { onSeeAll(libraryId, libraryName, collectionType) }) {
                    Text("See All", color = SashimiLink)
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            if (data == null) {
                items(PLACEHOLDER_CARD_COUNT) { PlaceholderPosterCard(isCircular = isYouTube) }
            } else {
                items(data.items, key = { it.id }) { item ->
                    val key = item.seriesId ?: item.id
                    ContextMenuBox(item = item, onClick = { onOpenDetail(item.id, libraryName) }) {
                        PosterCard(
                            item = item,
                            width = 110.dp,
                            libraryName = libraryName,
                            isCircular = isYouTube,
                            badgeCount = data.badgeCounts[key],
                        )
                    }
                }
            }
        }
    }
}

private const val PLACEHOLDER_CARD_COUNT = 6

/**
 * Stable-height stand-in for a [PosterCard] while a Recently Added row loads —
 * theme-colored rounded (or circular for YouTube) rects so row heights don't jump
 * and no spinner churns as rows scroll in.
 */
@Composable
private fun PlaceholderPosterCard(isCircular: Boolean) {
    val width = 110.dp
    Column(modifier = Modifier.width(width)) {
        Box(
            modifier =
                Modifier
                    .width(width)
                    .then(if (isCircular) Modifier.aspectRatio(1f) else Modifier.aspectRatio(2f / 3f))
                    .clip(if (isCircular) CircleShape else RoundedCornerShape(8.dp))
                    .background(SashimiCard),
        )
        Box(
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .height(12.dp)
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SashimiCard),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = SashimiTextPrimary,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
