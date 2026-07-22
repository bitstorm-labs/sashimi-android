package dev.bitstorm.sashimi.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.ui.components.PosterGrid
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary

/**
 * Search tab: poster-grid results (same card + column math as the library) with
 * a results count, year/type captions, and recent-search chips. Port of
 * MobileSearchView.
 */
@Composable
fun SearchScreen(
    isCompact: Boolean,
    onOpenDetail: (itemId: String, libraryName: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory())
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recentSearches.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Search",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = SashimiTextPrimary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        OutlinedTextField(
            value = state.searchText,
            onValueChange = vm::onSearchTextChange,
            placeholder = { Text("Movies, shows, and more") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            state.searchText.isEmpty() ->
                if (recent.isEmpty()) {
                    CenteredMessage("Search for movies, shows, and more.")
                } else {
                    RecentSearches(
                        recent = recent,
                        onApply = vm::applyRecentSearch,
                        onClear = vm::clearRecentSearches,
                    )
                }
            state.isSearching && state.results.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.results.isEmpty() ->
                CenteredMessage("No results found for \"${state.searchText}\"")
            else ->
                PosterGrid(
                    items = state.results,
                    isCompact = isCompact,
                    onOpenDetail = onOpenDetail,
                    headerText = if (state.results.size == 1) "1 result" else "${state.results.size} results",
                    caption = { subtitleText(it) },
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
private fun RecentSearches(
    recent: List<String>,
    onApply: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Recent Searches", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SashimiTextPrimary)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recent.forEach { query ->
                AssistChip(onClick = { onApply(query) }, label = { Text(query) })
            }
            AssistChip(onClick = onClear, label = { Text("Clear") })
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(message, color = SashimiTextSecondary)
    }
}

/** "2024 · Movie" secondary line under each result. Port of subtitleText. */
private fun subtitleText(item: BaseItemDto): String {
    val parts = mutableListOf<String>()
    item.productionYear?.let { parts.add(it.toString()) }
    when (item.type) {
        ItemType.MOVIE -> parts.add("Movie")
        ItemType.SERIES -> parts.add("Series")
        ItemType.EPISODE -> parts.add("Episode")
        ItemType.BOX_SET -> parts.add("Collection")
        else -> {}
    }
    return parts.joinToString(" · ")
}
