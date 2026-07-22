package dev.bitstorm.sashimi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.ui.components.ContextMenuBox
import dev.bitstorm.sashimi.ui.components.ContinueWatchingCard
import dev.bitstorm.sashimi.ui.components.PosterCard
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(4.dp),
            ) {
                Text(
                    "Sashimi",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SashimiTextPrimary,
                    modifier = Modifier.padding(end = 4.dp),
                )
                TextButton(onClick = { menuOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "Switch server", tint = SashimiTextTertiary)
                }
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
    var data by remember(libraryId) { mutableStateOf(RecentlyAddedData()) }
    var loaded by remember(libraryId) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(libraryId) {
        data = vm.loadRecentlyAdded(libraryId, libraryName, collectionType)
        loaded = true
    }

    if (loaded && data.items.isEmpty()) return

    val isYouTube = libraryName.contains("youtube", ignoreCase = true)
    val title = "Recently Added $libraryName".let { if (it.endsWith(" - Videos")) it.dropLast(9) else it }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SashimiTextPrimary, modifier = Modifier.weight(1f))
            if (data.items.size > 6) {
                TextButton(onClick = { onSeeAll(libraryId, libraryName, collectionType) }) {
                    Text("See All", color = SashimiLink)
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(data.items, key = { it.id }) { item ->
                val key = item.seriesId ?: item.id
                ContextMenuBox(item = item, onClick = { onOpenDetail(item.id, libraryName) }) {
                    PosterCard(
                        item = item,
                        width = 110.dp,
                        libraryName = libraryName,
                        isCircular = isYouTube && item.type == dev.bitstorm.sashimi.core.model.ItemType.SERIES,
                        badgeCount = data.badgeCounts[key],
                    )
                }
            }
        }
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
