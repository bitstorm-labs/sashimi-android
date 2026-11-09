package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.lists.SharedLists
import dev.bitstorm.sashimi.core.lists.Watchlist
import dev.bitstorm.sashimi.core.lists.WatchlistEntry
import dev.bitstorm.sashimi.core.lists.WatchlistTap
import dev.bitstorm.sashimi.core.lists.WatchlistView
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.components.PosterCard
import dev.bitstorm.sashimi.ui.requests.color
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.ImageUrls
import dev.bitstorm.sashimi.ui.util.rememberImageWidth

/** A not-on-server tile's dialog: the title, and what can be done. */
private data class NotOnServerNote(
    val title: String,
    val signIn: Boolean,
)

/**
 * The Watchlist (watchlist design, 2026-11-09): laid out like a list's page.
 * On-server titles are ordinary posters; missing ones are dimmed and tagged
 * Not on server / Requested, and open their Seerr page. It can't be renamed,
 * deleted or shared. Plex: a "Use Plex too?" card until linked or dismissed,
 * then a synced line, with Connect / Disconnect / Reconnect in the ⋯ menu.
 */
@Composable
fun WatchlistScreen(
    isCompact: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSeerrTarget: (SeerrTarget) -> Unit,
    onOpenRequestsSettings: () -> Unit,
) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf(false) }
    // Saved: Connect Plex comes back if Android kills the app behind Plex's page.
    var connecting by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<NotOnServerNote?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val onTap: (WatchlistEntry) -> Unit = { entry ->
        when (val tap = Watchlist.tapAction(entry, ServiceLocator.seerr.isActive)) {
            is WatchlistTap.Open -> onOpenDetail(tap.itemId)
            is WatchlistTap.Seerr -> onOpenSeerrTarget(tap.target)
            WatchlistTap.NotOnServer -> note = NotOnServerNote(entry.title, signIn = false)
            WatchlistTap.SignInToRequest -> note = NotOnServerNote(entry.title, signIn = true)
        }
    }

    Scaffold(
        topBar = { BackTopBar(title = "", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val view = state.view
            when {
                state.gone -> CenteredMessage("Your server no longer has a Watchlist.")
                view == null && state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                view == null ->
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error ?: "Couldn't load your Watchlist.", color = SashimiTextTertiary)
                        TextButton(onClick = vm::load) { Text("Try Again", color = SashimiAccent) }
                    }
                removing -> RemoveTitles(view, state, isCompact, vm, onDone = { removing = false })
                else ->
                    WatchlistGrid(
                        view = view,
                        state = state,
                        isCompact = isCompact,
                        vm = vm,
                        onTap = onTap,
                        onOpenDetail = onOpenDetail,
                        onConnect = { connecting = true },
                        onRemove = { removing = true },
                    )
            }
        }
    }

    if (connecting) {
        ConnectPlexDialog(
            onDismiss = { connecting = false },
            // The linker's change signal reloads the Watchlist.
            onLinked = { connecting = false },
        )
    }

    note?.let { n ->
        AlertDialog(
            onDismissRequest = { note = null },
            title = { Text(n.title) },
            text = {
                Text(if (n.signIn) "This isn't on your server. Sign into Requests to ask for this." else "This isn't on your server.")
            },
            confirmButton = { TextButton(onClick = { note = null }) { Text("OK") } },
            dismissButton =
                if (n.signIn) {
                    {
                        TextButton(onClick = {
                            note = null
                            onOpenRequestsSettings()
                        }) { Text("Requests Settings") }
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(text, color = SashimiTextTertiary, textAlign = TextAlign.Center) }
}

@Composable
private fun WatchlistGrid(
    view: WatchlistView,
    state: WatchlistUiState,
    isCompact: Boolean,
    vm: WatchlistViewModel,
    onTap: (WatchlistEntry) -> Unit,
    onOpenDetail: (String) -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val sidePadding = if (isCompact) 16.dp else 48.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The same column rule as a list's page.
        val spacing = 16.dp
        val avail = maxWidth - sidePadding * 2
        val target = if (isCompact) 104.dp else 150.dp
        val columns = maxOf(3, ((avail + spacing) / (target + spacing)).toInt())
        val cardWidth = (avail - spacing * (columns - 1)) / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(view, vm, onOpenDetail, onConnect, onRemove)
            }
            if (Watchlist.showsCard(view)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PlexCard(onConnect = onConnect, onNotNow = vm::dismissCard)
                }
            }
            if (view.entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Nothing here yet. Use Add to List on a movie or show.", color = SashimiTextTertiary)
                }
            }
            items(view.entries, key = { it.id }) { entry ->
                val item = entry.itemId?.let { state.items[SharedLists.normalizeId(it)] }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onTap(entry) }) {
                    if (item != null) {
                        PosterCard(item = item, width = cardWidth)
                    } else {
                        MissingTile(entry, Watchlist.tag(entry, state.badges[entry.id]), cardWidth)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    view: WatchlistView,
    vm: WatchlistViewModel,
    onOpenDetail: (String) -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val count = view.entries.size
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your list", color = SashimiTextSecondary)
        Text(Watchlist.NAME, color = SashimiTextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
        Text(
            "${if (count == 1) "1 title" else "$count titles"} · Private: only you can see it",
            color = SashimiTextTertiary,
            fontSize = 14.sp,
        )
        view.plex?.let { link ->
            if (link.needsReconnect) {
                Text("Plex stopped syncing. Reconnect to pick it up again.", color = SeerrBadge.PENDING.color(), fontSize = 14.sp)
            } else {
                Text("✓ Synced with Plex", color = SashimiTextSecondary, fontSize = 14.sp)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (view.entries.any { it.itemId != null }) {
                FilledTonalButton(onClick = { vm.shuffleTarget()?.let(onOpenDetail) }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Shuffle", modifier = Modifier.padding(start = 4.dp))
                }
            }
            WatchlistMenu(view, vm, onConnect, onRemove)
        }
    }
}

@Composable
private fun WatchlistMenu(
    view: WatchlistView,
    vm: WatchlistViewModel,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val link = view.plex
            if (link == null) {
                DropdownMenuItem(text = { Text("Connect Plex") }, onClick = {
                    open = false
                    onConnect()
                })
            } else {
                if (link.needsReconnect) {
                    DropdownMenuItem(text = { Text("Reconnect Plex") }, onClick = {
                        open = false
                        onConnect()
                    })
                }
                DropdownMenuItem(text = { Text("Disconnect Plex") }, onClick = {
                    open = false
                    confirmDisconnect = true
                })
            }
            if (view.entries.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Remove a Title") }, onClick = {
                    open = false
                    onRemove()
                })
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Plex?") },
            text = { Text("Your Watchlist stays as it is. Changes stop syncing with Plex.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    vm.disconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

/** "Use Plex too?" (the mockup's card). */
@Composable
private fun PlexCard(
    onConnect: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SashimiCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Use Plex too?", color = SashimiTextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Connect it and your Plex watchlist and this one stay the same.", color = SashimiTextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onConnect) { Text("Connect Plex") }
            TextButton(onClick = onNotNow) { Text("Not now") }
        }
    }
}

/** A title the server doesn't have: its poster dimmed (or its name), with the tag bottom-left. */
@Composable
private fun MissingTile(
    entry: WatchlistEntry,
    tag: dev.bitstorm.sashimi.core.lists.WatchlistTag?,
    width: Dp,
) {
    Column(Modifier.width(width)) {
        Box(Modifier.width(width).aspectRatio(2f / 3f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(if (entry.itemId == null) 0.45f else 1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SashimiCard),
                Alignment.Center,
            ) {
                if (entry.posterUrl != null) {
                    AsyncImage(
                        model = entry.posterUrl,
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        entry.title,
                        color = SashimiTextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            tag?.let {
                Text(
                    it.label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xD9121118))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            entry.title,
            color = SashimiTextTertiary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Remove a Title, as rows (the lists' REMOVE layout). */
@Composable
private fun RemoveTitles(
    view: WatchlistView,
    state: WatchlistUiState,
    isCompact: Boolean,
    vm: WatchlistViewModel,
    onDone: () -> Unit,
) {
    val sidePadding = if (isCompact) 16.dp else 48.dp
    LazyColumn(
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Remove a Title",
                    color = SashimiTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDone) { Text("Done") }
            }
        }
        items(view.entries, key = { it.id }) { entry ->
            val item = entry.itemId?.let { state.items[SharedLists.normalizeId(it)] }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SashimiCard)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = item?.let { ImageUrls.cardPoster(it, rememberImageWidth(40.dp)) } ?: entry.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .width(40.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SashimiBackgroundTile),
                )
                Text(
                    entry.title,
                    color = SashimiTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.remove(entry) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = SashimiAccent)
                }
            }
        }
    }
}

private val SashimiBackgroundTile = Color(0xFF2A2833)
