package dev.bitstorm.sashimi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import kotlin.math.roundToInt

private val RowHeight = 56.dp

/**
 * Home row order editor: long-press-drag to reorder, tap the check to enable /
 * disable a row. Port of the iOS `HomeRowOrderView` (drag reorder + toggle). Built
 * on a plain scrollable [Column] with fixed-height rows so the drag arithmetic is
 * simple and reliable for the handful of rows involved.
 */
@Composable
fun HomeRowOrderScreen(
    settings: HomeRowSettings,
    modifier: Modifier = Modifier,
) {
    val rows by settings.rows.collectAsStateWithLifecycle()
    val rowsState = rememberUpdatedState(rows)
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Home Rows", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Drag to reorder, tap the circle to show or hide a row.",
            style = MaterialTheme.typography.bodySmall,
            color = SashimiTextTertiary,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        rows.forEach { row ->
            val isDragging = draggingId == row.id
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(RowHeight)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .clip(RoundedCornerShape(8.dp))
                        .background(SashimiCard)
                        .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (row.isEnabled) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (row.isEnabled) "Hide row" else "Show row",
                    tint = if (row.isEnabled) SashimiAccent else SashimiTextTertiary,
                    modifier = Modifier.size(24.dp).clickable { settings.toggleRow(row.id) },
                )
                Text(
                    row.displayName,
                    color = SashimiTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Reorder",
                    tint = SashimiTextTertiary,
                    modifier =
                        Modifier.size(24.dp).pointerInput(row.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = row.id
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta.y
                                    val current = rowsState.value.indexOfFirst { it.id == draggingId }
                                    if (current < 0) return@detectDragGesturesAfterLongPress
                                    val shift = (dragOffset / rowHeightPx).roundToInt()
                                    val target = current + shift
                                    if (shift != 0 && target in rowsState.value.indices) {
                                        settings.moveRow(current, target)
                                        dragOffset -= shift * rowHeightPx
                                    }
                                },
                            )
                        },
                )
            }
        }
    }
}
