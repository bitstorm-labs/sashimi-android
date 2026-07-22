package dev.bitstorm.sashimi.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.browse.LibraryFilter
import dev.bitstorm.sashimi.core.browse.LibrarySort
import dev.bitstorm.sashimi.core.browse.SortDirection
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.components.PosterGrid
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import kotlinx.coroutines.launch

/**
 * Library browse: adaptive poster grid with sort/filter/shuffle/in-library
 * search + count line and empty states. Port of MobileLibraryBrowseView.
 */
@Composable
fun LibraryBrowseScreen(
    libraryId: String,
    libraryName: String,
    collectionType: String?,
    isCompact: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (itemId: String, libraryName: String?) -> Unit,
) {
    val vm: LibraryBrowseViewModel =
        viewModel(
            key = "library-$libraryId",
            factory = LibraryBrowseViewModel.Factory(libraryId, collectionType),
        )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val isYouTube = libraryName.contains("youtube", ignoreCase = true)

    Scaffold(
        topBar = {
            BackTopBar(title = libraryName, onBack = onBack) {
                if (!isYouTube) {
                    IconButton(onClick = {
                        scope.launch { vm.shuffleItem()?.let { onOpenDetail(it.id, libraryName) } }
                    }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
                    }
                }
                SortMenu(sort = state.sort, direction = state.direction, onSort = vm::setSort, onToggleDirection = vm::toggleDirection)
                FilterMenu(filter = state.filter, onFilter = vm::setFilter)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.searchText,
                onValueChange = vm::setSearchText,
                placeholder = { Text("Search this library") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            val displayed = state.displayedItems
            when {
                state.isLoading && state.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                displayed.isEmpty() ->
                    EmptyState(
                        searchText = state.searchText,
                        filter = state.filter,
                        onClearFilter = { vm.setFilter(LibraryFilter.ALL) },
                    )
                else ->
                    PosterGrid(
                        items = displayed,
                        isCompact = isCompact,
                        libraryName = libraryName,
                        headerText = countText(displayed.size, state.totalCount, state.searchText),
                        onOpenDetail = onOpenDetail,
                        onItemAction = vm::refreshAfterAction,
                        modifier = Modifier.weight(1f),
                    )
            }
        }
    }
}

private fun countText(
    shown: Int,
    total: Int,
    searchText: String,
): String =
    if (searchText.isNotBlank()) {
        "$shown of $total items"
    } else {
        "$total item${if (total == 1) "" else "s"}"
    }

@Composable
private fun SortMenu(
    sort: LibrarySort,
    direction: SortDirection,
    onSort: (LibrarySort) -> Unit,
    onToggleDirection: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        LibrarySort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                leadingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    open = false
                    onSort(option)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (direction == SortDirection.ASCENDING) "Ascending" else "Descending") },
            onClick = {
                open = false
                onToggleDirection()
            },
        )
    }
}

@Composable
private fun FilterMenu(
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.FilterList, contentDescription = "Filter")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        LibraryFilter.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                leadingIcon = { if (option == filter) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    open = false
                    onFilter(option)
                },
            )
        }
    }
}

@Composable
private fun EmptyState(
    searchText: String,
    filter: LibraryFilter,
    onClearFilter: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                searchText.isNotBlank() ->
                    Text("No results for \"$searchText\"", color = SashimiTextSecondary)
                filter != LibraryFilter.ALL -> {
                    Text("Nothing matches the ${filter.label} filter.", color = SashimiTextSecondary)
                    TextButton(onClick = onClearFilter) { Text("Clear Filter") }
                }
                else -> Text("This library is empty.", color = SashimiTextTertiary)
            }
        }
    }
}
