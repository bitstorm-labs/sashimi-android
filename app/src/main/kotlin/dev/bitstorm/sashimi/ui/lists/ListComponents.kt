package dev.bitstorm.sashimi.ui.lists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.lists.ListCover
import dev.bitstorm.sashimi.core.lists.PosterStack
import dev.bitstorm.sashimi.core.lists.SharedList
import dev.bitstorm.sashimi.core.lists.SharedLists
import dev.bitstorm.sashimi.core.lists.Watchlist
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.ImageUrls
import dev.bitstorm.sashimi.ui.util.rememberImageWidth

/** The "N new" pill on a subscribed row and on a list's header. */
internal val NewBlue = Color(0xFF3D7BFD)

/** "Public · 12 titles" for your own; "Marge · 18 titles" for someone else's. */
internal fun listMeta(list: SharedList): String {
    val titles = if (list.count == 1) "1 title" else "${list.count} titles"
    val who =
        when {
            list.isMine -> if (list.isPublic) "Public" else "Private"
            else -> list.ownerName ?: "Shared list"
        }
    return "$who · $titles"
}

/** Who made it, for the detail header. */
internal fun listByline(list: SharedList): String =
    when {
        list.isMine -> "Your list"
        list.ownerName != null -> "A list by ${list.ownerName}"
        else -> "Shared list"
    }

@Composable
internal fun NewPill(count: Int) {
    Text(
        "$count new",
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(NewBlue).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * How a title the server doesn't have is drawn (the mockups' grayscale(1)
 * brightness(.55)): grey and dark, so it reads as "not here" at a glance. An
 * alpha alone left it looking ordinary on the dark background.
 */
internal val NotOnServerFilter: ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(0f)
            timesAssign(ColorMatrix().apply { setToScale(0.55f, 0.55f, 0.55f, 1f) })
        },
    )

/** One poster of a cover stack: its picture, and whether it's dimmed (a Watchlist title not on the server). */
internal data class StackPoster(
    val model: String?,
    val dimmed: Boolean = false,
)

/** The posters a list's cover stacks ([SharedList.cover]). */
@Composable
internal fun listStackPosters(
    list: SharedList,
    posterWidth: Dp,
): List<StackPoster> {
    val px = rememberImageWidth(posterWidth)
    return when (val cover = list.cover) {
        is ListCover.Stack -> cover.posters.map { StackPoster(ImageUrls.cardPoster(it, px)) }
        ListCover.Empty -> emptyList()
    }
}

/** The Watchlist's cover: its first three entries, library posters where it has them, dimmed where not on the server. */
@Composable
internal fun watchlistStackPosters(
    slot: WatchlistSlot,
    posterWidth: Dp,
): List<StackPoster> {
    val ready = slot as? WatchlistSlot.Ready ?: return emptyList()
    val px = rememberImageWidth(posterWidth)
    return Watchlist.cover(ready.view).map { entry ->
        val item = entry.itemId?.let { ready.coverItems[SharedLists.normalizeId(it)] }
        StackPoster(
            model = item?.let { ImageUrls.cardPoster(it, px) } ?: entry.posterUrl,
            dimmed = entry.itemId == null,
        )
    }
}

/** The front poster's width in a cover [width] wide. */
internal fun stackPosterWidth(width: Dp): Dp = width / PosterStack.WIDTH_IN_POSTERS

/**
 * A list's cover (watchlist v2 §6, list-covers option A): the first poster
 * full size, the next two fanned behind it ([PosterStack]); [fanned] spreads
 * them a little (focus or hover). No posters: one placeholder poster with
 * [placeholder] on it.
 */
@Composable
internal fun PosterStackCover(
    posters: List<StackPoster>,
    width: Dp,
    fanned: Boolean,
    placeholder: ImageVector,
    modifier: Modifier = Modifier,
) {
    val posterWidth = stackPosterWidth(width)
    val posterHeight = posterWidth * 1.5f
    val fan by animateFloatAsState(if (fanned) 1f else 0f, label = "cover-fan")
    val rest = PosterStack.layers(posters.size, fanned = false)
    val open = PosterStack.layers(posters.size, fanned = true)
    // Room under the stack for the back posters' drop and turn.
    Box(modifier.width(width).height(posterHeight * 1.12f)) {
        rest.zip(open).forEach { (from, to) ->
            val x = lerp(from.offsetX, to.offsetX, fan)
            val turn = lerp(from.rotation, to.rotation, fan)
            val poster = posters.getOrNull(from.depth)
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .offset(x = posterWidth * x, y = posterHeight * from.offsetY + 4.dp)
                    .size(posterWidth * from.scale, posterHeight * from.scale)
                    .graphicsLayer {
                        rotationZ = turn
                        alpha = from.alpha
                    }.then(if (from.depth == 0) Modifier.shadow(10.dp, shape) else Modifier)
                    .clip(shape)
                    .background(SashimiCard),
                Alignment.Center,
            ) {
                if (poster == null) {
                    Icon(placeholder, contentDescription = null, tint = SashimiTextTertiary, modifier = Modifier.size(32.dp))
                } else {
                    AsyncImage(
                        model = poster.model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (poster.dimmed) NotOnServerFilter else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun lerp(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/** Whether a cover's card is focused, hovered or pressed: the stack fans out. */
@Composable
private fun MutableInteractionSource.fanned(): Boolean {
    val focused by collectIsFocusedAsState()
    val hovered by collectIsHoveredAsState()
    val pressed by collectIsPressedAsState()
    return focused || hovered || pressed
}

/** A card on the Lists page. */
@Composable
internal fun ListCard(
    list: SharedList,
    width: Dp,
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier =
            Modifier
                .width(width)
                .clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            PosterStackCover(
                listStackPosters(list, stackPosterWidth(width)),
                width,
                fanned = interaction.fanned(),
                placeholder = Icons.AutoMirrored.Filled.PlaylistPlay,
            )
            if (subscribed) {
                Text(
                    "✓ Subscribed",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xD9121118))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Text(list.name, color = SashimiTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listMeta(list), color = SashimiTextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The Watchlist's card, first in Your lists: a stack of its first three titles, else a bookmark. */
@Composable
internal fun WatchlistCard(
    slot: WatchlistSlot,
    width: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier =
            Modifier
                .width(width)
                .clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PosterStackCover(
            watchlistStackPosters(slot, stackPosterWidth(width)),
            width,
            fanned = interaction.fanned(),
            placeholder = Icons.Filled.Bookmark,
        )
        Text(Watchlist.NAME, color = SashimiTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            when (slot) {
                is WatchlistSlot.Ready ->
                    "Private \u00b7 " + if (slot.view.entries.size == 1) "1 title" else "${slot.view.entries.size} titles"
                WatchlistSlot.Failed -> "Couldn't load"
            },
            color = SashimiTextTertiary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** The dashed "+ New List" card at the end of Your lists. */
@Composable
internal fun NewListCard(
    width: Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .height(stackPosterWidth(width) * 1.5f * 1.12f)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, Color(0xFF4A4856), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = SashimiTextTertiary)
            Text("New List", color = SashimiTextTertiary, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * Name and Public switch for a new list. Private is the default: a public list
 * is visible to everyone on the server.
 */
@Composable
internal fun NewListDialog(
    confirmLabel: String,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onCreate: (name: String, isPublic: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New List") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Public", modifier = Modifier.weight(1f))
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                Text(
                    if (isPublic) "Everyone on the server can find and subscribe to it." else "Only you can see it.",
                    color = SashimiTextTertiary,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = { onCreate(name, isPublic) }) {
                Text(confirmLabel, color = SashimiAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One-field dialog for Rename. */
@Composable
internal fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename List") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim() != current, onClick = { onRename(name) }) {
                Text("Rename", color = SashimiAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
