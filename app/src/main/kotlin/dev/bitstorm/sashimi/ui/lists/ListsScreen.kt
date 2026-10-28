package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * The Lists page (shared lists, 2026-10-27): "Your lists" with a New List
 * card, then "From others" (everyone's public lists, with the creator's
 * name). Cards wrap, so the same page serves phone and the wide layout.
 */
@Composable
fun ListsScreen(
    isCompact: Boolean,
    onOpenList: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ListsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val padding = if (isCompact) 16.dp else 32.dp
            val cardWidth = cardWidth(maxWidth - padding * 2, isCompact)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("Lists", style = MaterialTheme.typography.headlineMedium, color = SashimiTextPrimary)
                when {
                    state.isLoading ->
                        Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) { CircularProgressIndicator() }
                    state.error != null ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error.orEmpty(), color = SashimiTextTertiary)
                            TextButton(onClick = vm::load) { Text("Try Again", color = SashimiAccent) }
                        }
                    else -> {
                        Section("Your lists") {
                            state.mine.forEach { list ->
                                ListCard(list, cardWidth, subscribed = list.id in state.subscribed) { onOpenList(list.id) }
                            }
                            NewListCard(cardWidth) { creating = true }
                        }
                        Section("From others") {
                            if (state.others.isEmpty()) {
                                Text("No one has shared a list yet.", color = SashimiTextTertiary)
                            }
                            state.others.forEach { list ->
                                ListCard(list, cardWidth, subscribed = list.id in state.subscribed) { onOpenList(list.id) }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (creating) {
        NewListDialog(
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onCreate = { name, isPublic ->
                creating = false
                vm.create(name, isPublic, onOpenList)
            },
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SashimiTextPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

/** Two cards across a phone; about 220 dp each when wide (the mockup). */
private fun cardWidth(
    available: Dp,
    isCompact: Boolean,
): Dp {
    val spacing = 16.dp
    val target = if (isCompact) 160.dp else 220.dp
    val count = maxOf(2, ((available + spacing) / (target + spacing)).toInt())
    return max(120.dp, (available - spacing * (count - 1)) / count)
}
