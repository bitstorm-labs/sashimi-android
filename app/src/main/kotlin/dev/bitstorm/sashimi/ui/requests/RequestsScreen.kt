package dev.bitstorm.sashimi.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.seerr.SeerrAuthState
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrImages
import dev.bitstorm.sashimi.core.seerr.SeerrRequestAction
import dev.bitstorm.sashimi.core.seerr.SeerrRequestFilter
import dev.bitstorm.sashimi.core.seerr.SeerrRequestRow
import dev.bitstorm.sashimi.core.seerr.SeerrRequestScope
import dev.bitstorm.sashimi.core.seerr.SeerrSearchItem
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * The Requests page (Apple #845 parity): Discover, like Seerr web's home,
 * and the request list, like Seerr web's /requests. A search field on top
 * finds any title Seerr knows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    onOpenTarget: (SeerrTarget) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val vm: RequestsViewModel = viewModel()
    val auth by ServiceLocator.seerr.state.collectAsStateWithLifecycle()
    val appTitle by ServiceLocator.seerr.applicationTitle.collectAsStateWithLifecycle()
    LaunchedEffect(auth.user?.id) { if (auth.user != null) vm.loadOnce() }

    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            dev.bitstorm.sashimi.ui.components
                .BackTopBar(title = "Requests", onBack = onBack)
        } else {
            Text(
                "Requests",
                style = MaterialTheme.typography.headlineMedium,
                color = SashimiTextPrimary,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (auth.user == null) {
            SignedOutNotice(expired = auth == SeerrAuthState.Expired, appTitle = appTitle, onOpenSettings = onOpenSettings)
            return
        }
        var query by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.search(it)
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search $appTitle") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (query.isNotBlank()) {
            SearchResults(vm, onOpenTarget)
            return
        }
        var tab by rememberSaveable { mutableStateOf(0) }
        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Discover") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Requests") })
        }
        if (tab == 0) DiscoverTab(vm, onOpenTarget) else RequestListTab(vm, onOpenTarget)
    }
}

@Composable
private fun SignedOutNotice(
    expired: Boolean,
    appTitle: String,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (expired) "Your $appTitle sign-in has expired." else "Sign in to $appTitle to request movies and shows.",
            color = SashimiTextSecondary,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenSettings) { Text(if (expired) "Sign In Again" else "Open Settings", color = SashimiAccent) }
    }
}

@Composable
private fun SearchResults(
    vm: RequestsViewModel,
    onOpenTarget: (SeerrTarget) -> Unit,
) {
    val items by vm.search.items.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items.forEach { item -> DiscoverCard(item, onOpenTarget) }
            }
        }
    }
}

@Composable
private fun DiscoverCard(
    item: SeerrSearchItem,
    onOpenTarget: (SeerrTarget) -> Unit,
    width: androidx.compose.ui.unit.Dp = 120.dp,
) {
    SeerrPosterCard(
        title = item.result.title,
        posterPath = item.result.posterPath,
        width = width,
        onClick = { onOpenTarget(item.target) },
        pill = item.discoverTag,
        subtitle = item.result.year?.toString(),
    )
}

@Composable
private fun DiscoverTab(
    vm: RequestsViewModel,
    onOpenTarget: (SeerrTarget) -> Unit,
) {
    val state by vm.discover.state.collectAsStateWithLifecycle()
    when {
        !state.hasLoaded -> Loading()
        state.rows.isEmpty() ->
            Message(state.errorMessage ?: "Nothing to discover right now.", onRetry = vm::reloadAll)
        else ->
            LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(state.rows, key = { it.slider.name }) { row ->
                    SeerrPosterRow(
                        title = row.title,
                        items = row.items,
                        key = { it.id },
                        onItemShown = { vm.loadMore(row.slider, it) },
                    ) { item, width -> DiscoverCard(item, onOpenTarget, width) }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestListTab(
    vm: RequestsViewModel,
    onOpenTarget: (SeerrTarget) -> Unit,
) {
    val state by vm.list.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<SeerrRequestAction, SeerrRequestRow>?>(null) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeerrRequestFilter.entries.forEach { f ->
                    FilterChip(selected = f == filter, onClick = { vm.setFilter(f) }, label = { Text(f.title) })
                }
            }
        }
        if (vm.list.canChooseScope) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp)) {
                    SeerrRequestScope.entries.forEachIndexed { index, s ->
                        SegmentedButton(
                            selected = s == scope,
                            onClick = { vm.setScope(s) },
                            shape = SegmentedButtonDefaults.itemShape(index, SeerrRequestScope.entries.size),
                        ) { Text(s.title) }
                    }
                }
            }
        }
        state.count?.let { count ->
            item {
                Text(
                    count.summary,
                    color = SashimiTextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp, 8.dp),
                )
            }
        }
        state.errorMessage?.let { error ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = SeerrBadge.DECLINED.color(), modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearListError) { Text("OK") }
                }
            }
        }
        when {
            !state.hasLoaded -> item { Loading() }
            state.rows.isEmpty() && state.errorMessage == null -> item { Message("No requests here.", onRetry = null) }
            else ->
                items(state.rows, key = { it.id }) { row ->
                    LaunchedEffect(row.id) { vm.loadMore(row) }
                    RequestListRow(
                        row = row,
                        showsRequester = vm.list.showsRequester,
                        actions = vm.list.actions(row),
                        busy = row.id in state.busy,
                        onOpen = { onOpenTarget(row.target) },
                        onAction = { action ->
                            if (action.isDestructive) confirm = action to row else vm.perform(action, row)
                        },
                    )
                    HorizontalDivider()
                }
        }
        if (state.isLoading && state.rows.isNotEmpty()) item { Loading(height = 64) }
    }

    confirm?.let { (action, row) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${action.title}?") },
            text = { Text("${row.displayTitle}, requested by ${row.requesterName}.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.perform(action, row)
                    confirm = null
                }) { Text(action.title, color = SeerrBadge.DECLINED.color()) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun RequestListRow(
    row: SeerrRequestRow,
    showsRequester: Boolean,
    actions: List<SeerrRequestAction>,
    busy: Boolean,
    onOpen: () -> Unit,
    onAction: (SeerrRequestAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(64.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(SashimiCard),
        ) {
            SeerrImages.poster(row.posterPath, width = 185)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, color = SashimiTextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.kindLabel, color = SashimiTextTertiary, style = MaterialTheme.typography.bodySmall)
            val who = if (showsRequester) "${row.requestedLabel()} by ${row.requesterName}" else row.requestedLabel()
            Text(
                who,
                color = SashimiTextTertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(row.requestStatus)
                row.mediaStatus?.let { StatusPill(it) }
            }
            row.progress?.let {
                LinearProgressIndicator(progress = {
                    it.toFloat()
                }, color = SeerrBadge.COMING.color(), modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
            if (actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        TextButton(onClick = { onAction(action) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(
                                action.title,
                                color = if (action.isDestructive) SeerrBadge.DECLINED.color() else SeerrBadge.AVAILABLE.color(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading(height: Int = 240) {
    Box(Modifier.fillMaxWidth().height(height.dp), Alignment.Center) { CircularProgressIndicator(color = SashimiAccent) }
}

@Composable
private fun Message(
    text: String,
    onRetry: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = SashimiTextTertiary, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Try Again", color = SashimiAccent) }
        }
    }
}
