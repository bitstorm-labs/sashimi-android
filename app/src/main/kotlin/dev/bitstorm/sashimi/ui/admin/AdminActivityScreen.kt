package dev.bitstorm.sashimi.ui.admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.admin.ActivityRows
import dev.bitstorm.sashimi.core.admin.AdminLoad
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import java.time.Instant

/** The latest activity-log entries, newest first; failures in red, IPs redacted. */
@Composable
fun AdminActivityScreen(
    onBack: () -> Unit,
    vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory()),
) {
    val load by vm.health.collectAsStateWithLifecycle()
    val forbidden by vm.forbidden.collectAsStateWithLifecycle()
    LaunchedEffect(forbidden) { if (forbidden) onBack() }
    val health = rememberLastReady(load)

    Scaffold(topBar = { BackTopBar("Activity", onBack) }) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            when {
                health != null -> {
                    val rows = remember(health) { ActivityRows.of(health.activity, Instant.now()) }
                    if (rows.isEmpty()) {
                        CenteredMessage("No activity yet")
                    } else {
                        LazyColumn(Modifier.fillMaxSize().widthIn(max = 720.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(rows, key = { it.id }) { r ->
                                DotRow(
                                    dot = if (r.isFailure) ErrorColor else null,
                                    text = r.text,
                                    detail = r.detail,
                                    trailing = r.`when`,
                                    textColor = if (r.isFailure) ErrorColor else androidx.compose.ui.graphics.Color.Unspecified,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                load is AdminLoad.Failed -> CenteredMessage("Can't reach the server. Retrying…")
                else -> CircularProgressIndicator(Modifier.padding(48.dp), color = SashimiAccent)
            }
        }
    }
}
