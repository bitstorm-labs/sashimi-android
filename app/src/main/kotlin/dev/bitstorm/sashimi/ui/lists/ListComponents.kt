package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.lists.SharedList
import dev.bitstorm.sashimi.core.model.ImageRef
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
 * A list's cover: the first four posters side by side (the approved mockup),
 * Jellyfin's generated playlist picture when the viewer can see no titles.
 */
@Composable
internal fun ListCover(
    list: SharedList,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val tileWidth = rememberImageWidth(width / 4)
    Box(
        modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(SashimiCard),
    ) {
        val posters = list.items.take(4)
        if (posters.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                posters.forEach { card ->
                    AsyncImage(
                        model = ImageUrls.cardPoster(card.item, tileWidth),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                repeat(4 - posters.size) { Box(Modifier.weight(1f).fillMaxHeight()) }
            }
        } else if (list.imageTag != null) {
            AsyncImage(
                model = ImageUrls.url(ImageRef(list.id, "Primary", list.imageTag), rememberImageWidth(width)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = SashimiTextTertiary,
                modifier = Modifier.align(Alignment.Center).size(36.dp),
            )
        }
    }
}

/** A card on the Lists page. */
@Composable
internal fun ListCard(
    list: SharedList,
    width: Dp,
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(width).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            ListCover(list, width)
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

/** The dashed "+ New List" card at the end of Your lists. */
@Composable
internal fun NewListCard(
    width: Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
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
