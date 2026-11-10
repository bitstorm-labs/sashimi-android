package dev.bitstorm.sashimi.ui.lists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.lists.SpotlightInfo
import dev.bitstorm.sashimi.ui.theme.SashimiBackground
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/** The title in the spotlight: what it says and its picture (a backdrop, else its poster). */
internal data class SpotlightContent(
    val info: SpotlightInfo,
    /** A wide picture; null falls back to [poster]. */
    val backdrop: String?,
    val poster: String?,
    /** A Watchlist title the server doesn't have. */
    val dimmed: Boolean = false,
    /** "Not on server" / "Requested", over the facts. */
    val tag: String? = null,
)

/**
 * The Spotlight list page (watchlist v2 §6, list-layouts option A). Wide: the
 * selected title's backdrop fills the page, its details and [actions] top left,
 * the list's posters in one row along the bottom; focus moves the spotlight and
 * a second press opens the title. Phone: the selected title's backdrop header
 * above the poster grid, and tapping a poster opens its page as before.
 *
 * [listControls] (Subscribe, Shuffle, the ⋯ menu) and [extras] (the Watchlist's
 * Plex card) stay at the top, under the spotlight's own actions.
 */
@Composable
internal fun <T> SpotlightPage(
    isCompact: Boolean,
    overline: String,
    entries: List<T>,
    key: (T) -> String,
    selectedKey: String?,
    spotlight: SpotlightContent?,
    onSelect: (String) -> Unit,
    onOpen: (T) -> Unit,
    emptyText: String,
    actions: @Composable () -> Unit,
    listControls: @Composable () -> Unit,
    extras: @Composable ColumnScope.() -> Unit = {},
    tile: @Composable (entry: T, width: Dp) -> Unit,
) {
    if (isCompact) {
        PhoneSpotlight(overline, entries, key, spotlight, onOpen, emptyText, actions, listControls, extras, tile)
    } else {
        WideSpotlight(overline, entries, key, selectedKey, spotlight, onSelect, onOpen, emptyText, actions, listControls, extras, tile)
    }
}

@Composable
private fun <T> PhoneSpotlight(
    overline: String,
    entries: List<T>,
    key: (T) -> String,
    spotlight: SpotlightContent?,
    onOpen: (T) -> Unit,
    emptyText: String,
    actions: @Composable () -> Unit,
    listControls: @Composable () -> Unit,
    extras: @Composable ColumnScope.() -> Unit,
    tile: @Composable (entry: T, width: Dp) -> Unit,
) {
    val sidePadding = 16.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Three across a phone, as the list grid always was.
        val spacing = 16.dp
        val avail = maxWidth - sidePadding * 2
        val columns = maxOf(3, ((avail + spacing) / (104.dp + spacing)).toInt())
        val cardWidth = (avail - spacing * (columns - 1)) / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.bleed(sidePadding).fillMaxWidth().aspectRatio(16f / 9f)) {
                        spotlight?.let { Backdrop(it) }
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = sidePadding)) {
                            Overline(overline)
                            spotlight?.let { Headline(it, titleSize = 26) }
                        }
                    }
                    spotlight?.info?.overview?.let { Overview(it, maxLines = 3) }
                    if (spotlight != null) actions()
                    listControls()
                    extras()
                    if (entries.isEmpty()) Text(emptyText, color = SashimiTextTertiary)
                }
            }
            items(entries, key = key) { entry ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(entry) }) { tile(entry, cardWidth) }
            }
        }
    }
}

@Composable
private fun <T> WideSpotlight(
    overline: String,
    entries: List<T>,
    key: (T) -> String,
    selectedKey: String?,
    spotlight: SpotlightContent?,
    onSelect: (String) -> Unit,
    onOpen: (T) -> Unit,
    emptyText: String,
    actions: @Composable () -> Unit,
    listControls: @Composable () -> Unit,
    extras: @Composable ColumnScope.() -> Unit,
    tile: @Composable (entry: T, width: Dp) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        spotlight?.let { Backdrop(it) }
        // The mockup's two washes: dark from the left behind the text, dark from the bottom behind the row.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0.3f to SashimiBackground, 0.75f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.4f to Color.Transparent, 0.75f to SashimiBackground),
            ),
        )
        val posterWidth = (maxWidth * 0.09f).coerceIn(96.dp, 150.dp)
        // A short screen (a phone on its side) keeps Play in view above the poster row.
        val short = maxHeight < 480.dp
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(0.5f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 48.dp, top = 8.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Overline(overline)
                spotlight?.let {
                    Headline(it, titleSize = if (short) 28 else 34)
                    it.info.overview?.let { text -> Overview(text, maxLines = if (short) 2 else 4) }
                    actions()
                }
                if (entries.isEmpty()) Text(emptyText, color = SashimiTextTertiary)
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(entries, key = key) { entry ->
                    val k = key(entry)
                    val selected = k == selectedKey
                    val scale by animateFloatAsState(if (selected) 1.06f else 1f, label = "spotlight-scale")
                    Box(
                        Modifier
                            .scale(scale)
                            .clip(RoundedCornerShape(10.dp))
                            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                            .onFocusChanged { if (it.isFocused) onSelect(k) }
                            // Touch: the first tap spotlights a title, the next opens it. D-pad focus already spotlit it.
                            .clickable { if (selected) onOpen(entry) else onSelect(k) }
                            .padding(4.dp),
                    ) { tile(entry, posterWidth) }
                }
            }
        }
        // The list's own line, buttons and cards stay in reach at the top, beside the spotlight,
        // on a scrim so they read over any backdrop.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.42f)
                .padding(top = 8.dp, end = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SashimiBackground.copy(alpha = 0.8f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listControls()
            extras()
        }
    }
}

@Composable
private fun Backdrop(
    content: SpotlightContent,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(modifier) {
        AsyncImage(
            model = content.backdrop ?: content.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (content.dimmed) NotOnServerFilter else null,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to SashimiBackground)))
    }
}

@Composable
private fun Overline(text: String) {
    Text(text.uppercase(), color = SashimiTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun Headline(
    content: SpotlightContent,
    titleSize: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            content.info.title,
            color = SashimiTextPrimary,
            fontSize = titleSize.sp,
            lineHeight = (titleSize + 4).sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = listOfNotNull(content.tag) + content.info.facts
        if (facts.isNotEmpty()) {
            Text(facts.joinToString(" · "), color = SashimiTextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Overview(
    text: String,
    maxLines: Int,
) {
    Text(text, color = SashimiTextSecondary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Lets a grid's full-width header run edge to edge past the grid's side padding. */
private fun Modifier.bleed(side: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = side.roundToPx() * 2
        val placeable =
            measurable.measure(
                constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.maxWidth + extra),
            )
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }
