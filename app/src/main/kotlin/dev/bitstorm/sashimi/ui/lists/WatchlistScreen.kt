package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.bitstorm.sashimi.core.lists.ListSpotlight
import dev.bitstorm.sashimi.core.lists.SharedLists
import dev.bitstorm.sashimi.core.lists.Watchlist
import dev.bitstorm.sashimi.core.lists.WatchlistEntry
import dev.bitstorm.sashimi.core.lists.WatchlistRequest
import dev.bitstorm.sashimi.core.lists.WatchlistTap
import dev.bitstorm.sashimi.core.lists.WatchlistTitleRef
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

/**
 * The Watchlist (watchlist design, 2026-11-09): laid out like a list's page.
 * On-server titles are ordinary posters; missing ones are dimmed and tagged
 * Not on server / Requested, and open their title page (v2 §3). ＋ Add
 * searches for titles to add (plugin 0.3.0). It can't be renamed,
 * deleted or shared. Plex: a "Use Plex too?" card until linked or dismissed,
 * then a synced line, with Connect / Disconnect / Reconnect in the ⋯ menu.
 */
@Composable
fun WatchlistScreen(
    isCompact: Boolean,
    /** Null when it is a primary destination (side menu, phone bar): no Back arrow. */
    onBack: (() -> Unit)?,
    onOpenDetail: (String) -> Unit,
    onPlay: (String) -> Unit,
    onOpenSeerrTarget: (SeerrTarget) -> Unit,
    onOpenRequestsSettings: () -> Unit,
    onOpenTitle: (WatchlistTitleRef) -> Unit,
    onAdd: () -> Unit,
) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf(false) }
    // Saved: Connect Plex comes back if Android kills the app behind Plex's page.
    var connecting by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val onTap: (WatchlistEntry) -> Unit = { entry ->
        when (val tap = Watchlist.tapAction(entry)) {
            is WatchlistTap.Open -> onOpenDetail(tap.itemId)
            is WatchlistTap.Details -> onOpenTitle(tap.ref)
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
                        onPlay = onPlay,
                        onOpenSeerrTarget = onOpenSeerrTarget,
                        onOpenRequestsSettings = onOpenRequestsSettings,
                        onOpenTitle = onOpenTitle,
                        onAdd = onAdd,
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
    onPlay: (String) -> Unit,
    onOpenSeerrTarget: (SeerrTarget) -> Unit,
    onOpenRequestsSettings: () -> Unit,
    onOpenTitle: (WatchlistTitleRef) -> Unit,
    onAdd: () -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val canAdd by vm.canAdd.collectAsStateWithLifecycle()
    val selectedKey by vm.spotlight.selected.collectAsStateWithLifecycle()
    val loaded by vm.spotlight.loaded.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val selected = view.entries.firstOrNull { it.id == selectedKey }
    // The full item once it arrives, else the outline the page already has.
    val selectedItem = selected?.itemId?.let { loaded[it] ?: state.items[SharedLists.normalizeId(it)] }
    val imageWidth = rememberImageWidth(LocalConfiguration.current.screenWidthDp.dp)
    val count = view.entries.size
    SpotlightPage(
        isCompact = isCompact,
        overline = "${Watchlist.NAME} · ${if (count == 1) "1 title" else "$count titles"}",
        entries = view.entries,
        key = { it.id },
        selectedKey = selectedKey,
        spotlight =
            selected?.let { entry ->
                if (selectedItem != null) {
                    SpotlightContent(
                        info = ListSpotlight.info(selectedItem),
                        backdrop = ImageUrls.detailBackdrop(selectedItem, isYouTubeSeries = false, widthPx = imageWidth),
                        poster = ImageUrls.cardPoster(selectedItem, imageWidth),
                    )
                } else if (entry.itemId == null) {
                    // Not on the server: the provider's backdrop once Details answers, else the poster, dimmed.
                    val spot = ListSpotlight.missing(entry, missing[entry.id])
                    SpotlightContent(
                        info = spot.info,
                        backdrop = spot.backdrop,
                        poster = spot.poster,
                        dimmed = spot.dimmed,
                        tag = Watchlist.tag(entry, state.badges[entry.id])?.label,
                    )
                } else {
                    SpotlightContent(
                        info = ListSpotlight.info(entry),
                        backdrop = null,
                        poster = entry.posterUrl,
                        tag = Watchlist.tag(entry, state.badges[entry.id])?.label,
                    )
                }
            },
        onSelect = { key -> view.entries.firstOrNull { it.id == key }?.let(vm::select) },
        onOpen = onTap,
        emptyText =
            if (canAdd) {
                "Nothing here yet. Use ＋ Add, or Add to List on a movie or show."
            } else {
                "Nothing here yet. Use Add to List on a movie or show."
            },
        actions = {
            selected?.let { entry ->
                SpotlightActions(entry, selectedItem, vm, onOpenDetail, onPlay, onOpenSeerrTarget, onOpenRequestsSettings, onOpenTitle)
            }
        },
        listControls = { Header(view, vm, canAdd, onOpenDetail, onAdd, onConnect, onRemove) },
        extras = {
            if (Watchlist.showsCard(view)) PlexCard(onConnect = onConnect, onNotNow = vm::dismissCard)
        },
    ) { entry, width ->
        val item = entry.itemId?.let { state.items[SharedLists.normalizeId(it)] }
        if (item != null) {
            PosterCard(item = item, width = width)
        } else {
            MissingTile(entry, Watchlist.tag(entry, state.badges[entry.id]), width)
        }
    }
}

/** Play and Details for a title on the server; Request or Sign in to Requests for one that isn't. */
@Composable
private fun SpotlightActions(
    entry: WatchlistEntry,
    item: dev.bitstorm.sashimi.core.model.BaseItemDto?,
    vm: WatchlistViewModel,
    onOpenDetail: (String) -> Unit,
    onPlay: (String) -> Unit,
    onOpenSeerrTarget: (SeerrTarget) -> Unit,
    onOpenRequestsSettings: () -> Unit,
    onOpenTitle: (WatchlistTitleRef) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item != null) {
            Button(onClick = { vm.play(item, onPlay) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Play", modifier = Modifier.padding(start = 4.dp))
            }
            FilledTonalButton(onClick = { onOpenDetail(item.id) }) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Details", modifier = Modifier.padding(start = 4.dp))
            }
            return@FlowRow
        }
        val ref = WatchlistTitleRef.of(entry)
        when (val request = Watchlist.requestAction(ref, ServiceLocator.seerr.isActive)) {
            is WatchlistRequest.Seerr -> Button(onClick = { onOpenSeerrTarget(request.target) }) { Text("Request") }
            WatchlistRequest.SignIn -> Button(onClick = onOpenRequestsSettings) { Text("Sign in to Requests") }
            WatchlistRequest.Unavailable -> Unit
        }
        FilledTonalButton(onClick = { onOpenTitle(ref) }) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Details", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** The Watchlist's own line and buttons, under the spotlight: private, the Plex line, Shuffle, ⋯. */
@Composable
private fun Header(
    view: WatchlistView,
    vm: WatchlistViewModel,
    canAdd: Boolean,
    onOpenDetail: (String) -> Unit,
    onAdd: () -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your list · Private: only you can see it", color = SashimiTextTertiary, fontSize = 13.sp)
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
            if (canAdd) {
                OutlinedButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (view.entries.any { it.itemId != null }) {
                OutlinedButton(onClick = { vm.shuffleTarget()?.let(onOpenDetail) }) {
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(SashimiCard),
                Alignment.Center,
            ) {
                if (entry.posterUrl != null) {
                    AsyncImage(
                        model = entry.posterUrl,
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (entry.itemId == null) NotOnServerFilter else null,
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
