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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.lists.ListItem
import dev.bitstorm.sashimi.core.lists.SharedList
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.components.PosterCard
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.ImageUrls
import dev.bitstorm.sashimi.ui.util.rememberImageWidth

/** How the owner is editing the list's titles, if at all. */
private enum class EditMode { NONE, REORDER, REMOVE }

/**
 * One list. Everyone gets the name, creator, count ("3 new since you last
 * looked" for a subscriber), Subscribe and Shuffle, and the titles as a
 * poster grid with NEW marks. The owner also gets Edit List: Rename,
 * Make Public/Private, Reorder Titles, Remove a Title, Delete List.
 */
@Composable
fun ListDetailScreen(
    listId: String,
    isCompact: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val vm: ListDetailViewModel = viewModel(key = "list-$listId", factory = ListDetailViewModel.Factory(listId))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editMode by remember { mutableStateOf(EditMode.NONE) }

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    Scaffold(
        topBar = { BackTopBar(title = "", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = state.list
            when {
                list == null && state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.gone -> Message("This list is no longer available.")
                list == null -> Message(state.error ?: "Couldn't load this list.")
                editMode != EditMode.NONE ->
                    EditTitles(list, editMode, isCompact, vm, onDone = { editMode = EditMode.NONE })
                else ->
                    ListGrid(list, state, isCompact, vm, onOpenDetail, onEdit = { editMode = it })
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(text, color = SashimiTextTertiary) }
}

@Composable
private fun ListGrid(
    list: SharedList,
    state: ListDetailUiState,
    isCompact: Boolean,
    vm: ListDetailViewModel,
    onOpenDetail: (String) -> Unit,
    onEdit: (EditMode) -> Unit,
) {
    val sidePadding = if (isCompact) 16.dp else 48.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Three across a phone (the approved mockup); about 150 dp posters when wide.
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
                Header(list, state, vm, onOpenDetail, onEdit)
            }
            if (list.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (list.isMine) "Nothing here yet. Use Add to List on a movie or show." else "This list is empty.",
                        color = SashimiTextTertiary,
                    )
                }
            }
            items(list.items, key = { it.display.id }) { card ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpenDetail(card.item.id) }) {
                    PosterCard(item = card.item, width = cardWidth, isNew = card.display.id in state.newIds)
                }
            }
        }
    }
}

@Composable
private fun Header(
    list: SharedList,
    state: ListDetailUiState,
    vm: ListDetailViewModel,
    onOpenDetail: (String) -> Unit,
    onEdit: (EditMode) -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!list.isMine && list.ownerName != null) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF8A5A9E)), Alignment.Center) {
                    Text(list.ownerName!!.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Text(listByline(list), color = SashimiTextSecondary)
        }
        Text(list.name, color = SashimiTextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
        Text(countLine(list, state), color = SashimiTextTertiary, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.subscribed) {
                Button(
                    onClick = vm::toggleSubscribe,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF111111)),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Subscribed", modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                Button(onClick = vm::toggleSubscribe) { Text("Subscribe") }
            }
            if (list.items.isNotEmpty()) {
                FilledTonalButton(onClick = { vm.shuffleTarget()?.let(onOpenDetail) }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Shuffle", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (list.isMine) OwnerMenu(list, state.publicSaving, vm, onEdit)
        }
    }
}

private fun countLine(
    list: SharedList,
    state: ListDetailUiState,
): String {
    val titles = if (list.count == 1) "1 title" else "${list.count} titles"
    return when {
        state.subscribed && state.newIds.isNotEmpty() -> "$titles · ${state.newIds.size} new since you last looked"
        list.isMine ->
            "$titles · " +
                if (state.publicSaving) {
                    "Saving…"
                } else if (list.isPublic) {
                    "Public: everyone on the server can see and subscribe"
                } else {
                    "Private: only you can see it"
                }
        else -> titles
    }
}

@Composable
private fun OwnerMenu(
    list: SharedList,
    publicSaving: Boolean,
    vm: ListDetailViewModel,
    onEdit: (EditMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Edit List", modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                open = false
                renaming = true
            })
            DropdownMenuItem(text = { Text(if (list.isPublic) "Make Private" else "Make Public") }, enabled = !publicSaving, onClick = {
                open = false
                vm.setPublic(!list.isPublic)
            })
            if (list.items.size > 1) {
                DropdownMenuItem(text = { Text("Reorder Titles") }, onClick = {
                    open = false
                    onEdit(EditMode.REORDER)
                })
            }
            if (list.items.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Remove a Title") }, onClick = {
                    open = false
                    onEdit(EditMode.REMOVE)
                })
            }
            DropdownMenuItem(text = { Text("Delete List", color = Color(0xFFFF6B6B)) }, onClick = {
                open = false
                confirmDelete = true
            })
        }
    }
    if (renaming) {
        RenameDialog(list.name, onDismiss = { renaming = false }) {
            renaming = false
            vm.rename(it)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${list.name}”?") },
            text = { Text("It disappears for everyone subscribed to it. The movies and shows stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete()
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Reorder (up/down) or Remove (trash) as plain rows: works by touch and by D-pad alike. */
@Composable
private fun EditTitles(
    list: SharedList,
    mode: EditMode,
    isCompact: Boolean,
    vm: ListDetailViewModel,
    onDone: () -> Unit,
) {
    val sidePadding = if (isCompact) 16.dp else 48.dp
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mode == EditMode.REORDER) "Reorder Titles" else "Remove a Title",
                    color = SashimiTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDone) { Text("Done") }
            }
        }
        itemsIndexed(list.items, key = { _, card -> card.display.id }) { index, card ->
            EditRow(card, index, list.items.size, mode, vm)
        }
    }
}

@Composable
private fun EditRow(
    card: ListItem,
    index: Int,
    size: Int,
    mode: EditMode,
    vm: ListDetailViewModel,
) {
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
            model = ImageUrls.cardPoster(card.item, rememberImageWidth(40.dp)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)),
        )
        Text(
            card.item.seriesName?.takeIf { !card.display.isSeries } ?: card.item.name,
            color = SashimiTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (mode == EditMode.REORDER) {
            IconButton(enabled = index > 0, onClick = { vm.move(card, -1) }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(enabled = index < size - 1, onClick = { vm.move(card, +1) }) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
        } else {
            IconButton(onClick = { vm.remove(card) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = SashimiAccent)
            }
        }
    }
}
