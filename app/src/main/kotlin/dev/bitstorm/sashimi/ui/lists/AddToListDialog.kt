package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * The Add to List picker: your lists (tap one to add; "Already on this list"
 * where it is), then "New List…" (name and Public switch). [onResult] gets the
 * line to show once the picker closes ("Added to Date Night").
 */
@Composable
fun AddToListDialog(
    item: BaseItemDto,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val vm: AddToListViewModel = viewModel(key = "add-to-list-${item.id}", factory = AddToListViewModel.Factory(item))
    val state by vm.state.collectAsStateWithLifecycle()
    var newList by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(vm) { vm.load() }
    val title = item.name

    if (newList) {
        NewListDialog(
            confirmLabel = "Create and Add",
            busy = state.busy,
            onDismiss = { newList = false },
            onCreate = { name, isPublic ->
                vm.createAndAdd(name, isPublic) {
                    onDismiss()
                    onResult(it)
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add “$title” to…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                    state.error != null -> Text(state.error.orEmpty(), color = SashimiTextTertiary)
                    state.rows.isEmpty() ->
                        Text(
                            "You have no lists yet.",
                            color = SashimiTextTertiary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                }
                state.rows.forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !row.alreadyOn && !state.busy) {
                                vm.add(row.list) {
                                    onDismiss()
                                    onResult(it)
                                }
                            }.padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            row.list.name,
                            color = if (row.alreadyOn) SashimiTextTertiary else SashimiTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (row.alreadyOn) "Already on this list" else pickerMeta(row.list),
                            color = SashimiTextTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (!state.isLoading && state.error == null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !state.busy) { newList = true }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = SashimiTextPrimary)
                        Text("New List…", color = SashimiTextPrimary, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Public · 12" (the mockup's picker line). */
private fun pickerMeta(list: dev.bitstorm.sashimi.core.lists.SharedList): String =
    "${if (list.isPublic) "Public" else "Private"} \u00b7 ${list.count}"
