package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.lists.WatchlistSearchHit
import dev.bitstorm.sashimi.core.lists.WatchlistTitleRef
import dev.bitstorm.sashimi.core.lists.WatchlistV2
import dev.bitstorm.sashimi.core.model.ImageRef
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrSeasonPlan
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.requests.SeasonPickerDialog
import dev.bitstorm.sashimi.ui.requests.SeerrActionArea
import dev.bitstorm.sashimi.ui.requests.color
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiBackground
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.ImageUrls
import dev.bitstorm.sashimi.ui.util.rememberImageWidth

/** "✓ Added": a title already on the Watchlist. */
private val AddedGreen = Color(0xFF9FDCB2)

/**
 * ＋ Add (watchlist v2 §2, mockup screen 2): one search, two groups. ＋ adds
 * a title straight from the list; tapping it opens its page (the library page
 * on the server, else the title page).
 */
@Composable
fun WatchlistAddScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenTitle: (WatchlistTitleRef) -> Unit,
) {
    val vm: WatchlistAddViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LifecycleResumeEffect(vm) {
        vm.onScreenResumed()
        onPauseOrDispose {}
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(title = "Add to Watchlist", onBack = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.fillMaxSize().widthIn(max = 720.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = state.text,
                            onValueChange = vm::onTextChange,
                            placeholder = { Text("Movies and shows") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    when {
                        state.unavailable ->
                            item { Note("Your server can't search for titles to add yet.") }
                        state.error != null ->
                            item {
                                Column(Modifier.padding(top = 16.dp)) {
                                    Text(state.error.orEmpty(), color = SashimiTextTertiary)
                                    TextButton(onClick = vm::retry) { Text("Try Again", color = SashimiAccent) }
                                }
                            }
                        state.result == null && state.isSearching ->
                            item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                        else ->
                            state.result?.let { result ->
                                group("On your server", result.onServer, state, vm, onOpenDetail, onOpenTitle)
                                group("Not on server", result.notOnServer, state, vm, onOpenDetail, onOpenTitle)
                            }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.group(
    title: String,
    hits: List<WatchlistSearchHit>,
    state: WatchlistAddUiState,
    vm: WatchlistAddViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenTitle: (WatchlistTitleRef) -> Unit,
) {
    item(key = "h-$title") {
        Text(
            title.uppercase(),
            color = SashimiTextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
    }
    if (hits.isEmpty()) {
        item(key = "e-$title") { Text("No results", color = SashimiTextTertiary) }
    }
    items(hits, key = { "$title-${WatchlistV2.key(it)}" }) { hit ->
        val itemId = hit.itemId
        HitRow(
            hit = hit,
            added = state.isAdded(hit),
            adding = WatchlistV2.key(hit) in state.adding,
            onAdd = { vm.add(hit) },
            onOpen = { if (itemId != null) onOpenDetail(itemId) else onOpenTitle(WatchlistTitleRef.of(hit)) },
        )
    }
}

@Composable
private fun HitRow(
    hit: WatchlistSearchHit,
    added: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val px = rememberImageWidth(48.dp)
        // On the server: Jellyfin's own picture (posterUrl is only there when a remote hit had one).
        val poster = hit.itemId?.let { ImageUrls.url(ImageRef(it, "Primary", null), px) } ?: hit.posterUrl
        AsyncImage(
            model = poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .width(48.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SashimiCard),
        )
        Column(Modifier.weight(1f)) {
            Text(hit.title, color = SashimiTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(WatchlistV2.hitLine(hit), color = SashimiTextTertiary, fontSize = 13.sp)
        }
        when {
            added ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = AddedGreen, modifier = Modifier.size(16.dp))
                    Text("Added", color = AddedGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            adding -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else ->
                IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ${hit.title} to Watchlist", tint = SashimiAccent)
                }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = SashimiTextTertiary, modifier = Modifier.padding(top = 16.dp))
}

/**
 * A title the server doesn't have (watchlist v2 §3, mockup screens 3 and 4):
 * backdrop, poster and facts from the server's metadata providers, the
 * Watchlist toggle, and Request (signed into Requests) or Sign in to
 * Requests. Once the title is in the library, the library page takes over.
 */
@Composable
fun WatchlistTitleScreen(
    ref: WatchlistTitleRef,
    onBack: () -> Unit,
    onOpenLibraryItem: (String) -> Unit,
    onOpenRequestsSettings: () -> Unit,
) {
    val vm: WatchlistTitleViewModel =
        viewModel(
            key = "wl-title-${ref.type}-${ref.tmdbId}-${ref.tvdbId}-${ref.imdbId}",
            factory = viewModelFactory { initializer { WatchlistTitleViewModel(ref) } },
        )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<SeerrSeasonPlan?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.libraryItemId) { state.libraryItemId?.let(onOpenLibraryItem) }
    LifecycleResumeEffect(vm) {
        vm.refreshRequest()
        onPauseOrDispose {}
    }

    val details = (state.details as? TitleDetailsState.Ready)?.details
    val title = details?.title ?: ref.title
    val poster = details?.posterUrl ?: ref.posterUrl

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                AsyncImage(
                    model = details?.backdropUrl ?: poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, SashimiBackground))))
            }
            Column(
                Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(
                        model = poster,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(110.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SashimiCard),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = SashimiTextPrimary)
                        val facts = details?.let(WatchlistV2::facts) ?: listOfNotNull(ref.year?.toString())
                        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = SashimiTextSecondary)
                    }
                }
                Text("Not on your server", color = SashimiTextTertiary)
                when (val d = state.details) {
                    TitleDetailsState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    TitleDetailsState.Unavailable -> Text("Details aren't available.", color = SashimiTextTertiary)
                    is TitleDetailsState.Failed ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.message, color = SashimiTextTertiary, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::load) { Text("Try Again", color = SashimiAccent) }
                        }
                    is TitleDetailsState.Ready -> d.details.overview?.let { Text(it, color = SashimiTextSecondary) }
                }
                Actions(vm, state, onOpenRequestsSettings, onChooseSeasons = { picking = it })
                details?.source?.let { Text("Details from $it", color = SashimiTextTertiary, fontSize = 12.sp) }
                Box(Modifier.height(24.dp))
            }
        }
        BackTopBar(title = "", onBack = onBack, containerColor = Color.Transparent)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    picking?.let { plan ->
        SeasonPickerDialog(
            initial = plan,
            title = title,
            onDismiss = { picking = null },
            onRequest = {
                vm.requestSeasons(it)
                picking = null
            },
        )
    }
}

@Composable
private fun Actions(
    vm: WatchlistTitleViewModel,
    state: WatchlistTitleUiState,
    onOpenRequestsSettings: () -> Unit,
    onChooseSeasons: (SeerrSeasonPlan) -> Unit,
) {
    val request by vm.request.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = state.entryId != null
            FilledTonalButton(onClick = vm::toggleWatchlist, enabled = state.watchlistKnown && !state.saving) {
                if (on) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = AddedGreen, modifier = Modifier.size(18.dp))
                    Text("On Watchlist", modifier = Modifier.padding(start = 4.dp))
                } else {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add to Watchlist", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (request == null) {
                if (vm.ref.seerrTarget() != null) Button(onClick = onOpenRequestsSettings) { Text("Sign in to Requests") }
            }
        }
        request?.let { model ->
            val seerrState by model.state.collectAsStateWithLifecycle()
            SeerrActionArea(seerrState, onRequestMovie = vm::requestMovie, onChooseSeasons = { model.seasonPlan()?.let(onChooseSeasons) })
            seerrState.quotaNote?.let { Text(it, color = SashimiTextTertiary, style = MaterialTheme.typography.bodySmall) }
            seerrState.errorMessage?.let { Text(it, color = SeerrBadge.DECLINED.color()) }
        }
    }
}
