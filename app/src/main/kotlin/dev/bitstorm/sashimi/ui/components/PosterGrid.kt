package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * Adaptive poster grid shared by the library browse, search, and See All
 * screens. Column count comes from [gridMetrics] (identical to the Swift
 * gridMetrics) so covers fill their column; an optional [headerText] (count
 * line) spans the full width and an optional [caption] renders under each card.
 */
@Composable
fun PosterGrid(
    items: List<BaseItemDto>,
    isCompact: Boolean,
    onOpenDetail: (itemId: String, libraryName: String?) -> Unit,
    modifier: Modifier = Modifier,
    libraryName: String? = null,
    headerText: String? = null,
    badgeCounts: Map<String, Int> = emptyMap(),
    onItemAction: () -> Unit = {},
    caption: ((BaseItemDto) -> String?)? = null,
) {
    val isYouTube = libraryName?.contains("youtube", ignoreCase = true) == true

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = gridMetrics(maxWidth, isCompact)
        LazyVerticalGrid(
            columns = GridCells.Fixed(metrics.columns),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (headerText != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(headerText, color = SashimiTextTertiary, fontSize = 12.sp)
                }
            }
            items(items, key = { it.id }) { item ->
                Column {
                    ContextMenuBox(
                        item = item,
                        onClick = { onOpenDetail(item.id, libraryName) },
                        onAction = onItemAction,
                    ) {
                        PosterCard(
                            item = item,
                            width = metrics.cardWidth,
                            libraryName = libraryName,
                            isCircular = isYouTube && item.type == ItemType.SERIES,
                            badgeCount = badgeCounts[item.seriesId ?: item.id],
                        )
                    }
                    val captionText = caption?.invoke(item)
                    if (captionText != null) {
                        Text(
                            captionText,
                            color = SashimiTextTertiary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp).width(metrics.cardWidth),
                        )
                    }
                }
            }
        }
    }
}
