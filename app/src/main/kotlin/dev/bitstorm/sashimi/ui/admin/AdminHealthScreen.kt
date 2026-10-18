package dev.bitstorm.sashimi.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.admin.AdminLoad
import dev.bitstorm.sashimi.core.admin.HardwareTranscoding
import dev.bitstorm.sashimi.core.admin.HealthSnapshot
import dev.bitstorm.sashimi.core.admin.LibraryScan
import dev.bitstorm.sashimi.core.admin.RelativeTime
import dev.bitstorm.sashimi.core.admin.StorageRow
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.downloads.formatBytes
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import java.time.Instant
import kotlin.math.roundToInt

/** Server health: library scan, failed tasks, storage and hardware transcoding. */
@Composable
fun AdminHealthScreen(
    onBack: () -> Unit,
    vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory()),
) {
    val load by vm.health.collectAsStateWithLifecycle()
    val forbidden by vm.forbidden.collectAsStateWithLifecycle()
    LaunchedEffect(forbidden) { if (forbidden) onBack() }
    val health = rememberLastReady(load)

    Scaffold(topBar = { BackTopBar("Health", onBack) }) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            when {
                health != null -> HealthList(health)
                load is AdminLoad.Failed -> CenteredMessage("Can't reach the server. Retrying…")
                else -> CircularProgressIndicator(Modifier.padding(48.dp), color = SashimiAccent)
            }
        }
    }
}

@Composable
private fun HealthList(health: HealthSnapshot) {
    val now = remember(health) { Instant.now() }
    LazyColumn(
        Modifier.fillMaxSize().widthIn(max = 720.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminPanel {
                val name = listOfNotNull(health.info.serverName, health.info.version).joinToString(" ")
                DotRow(dot = null, text = "Server", trailing = name)
                LibraryScan.summary(health.tasks, now)?.let { scan ->
                    val failed = scan.startsWith("Failed") || scan.startsWith("Aborted")
                    DotRow(dot = if (failed) ErrorColor else null, text = "Library scan", trailing = scan)
                }
                val hw = health.encoding?.hardwareAccelerationType
                if (health.encoding != null) {
                    DotRow(
                        dot = if (HardwareTranscoding.isOn(hw)) DirectFg else WarningColor,
                        text = "Hardware transcoding",
                        trailing = HardwareTranscoding.label(hw),
                    )
                }
            }
        }
        val failed = health.tasks.filter { it.lastExecutionResult?.status in setOf("Failed", "Aborted") }
        item { AdminSectionTitle("Scheduled tasks") }
        item {
            AdminPanel {
                if (failed.isEmpty()) DotRow(dot = DirectFg, text = "No failed tasks", trailing = "")
                failed.forEach {
                    DotRow(
                        dot = ErrorColor,
                        text = "${it.name} failed",
                        trailing = RelativeTime.short(it.lastExecutionResult?.endTimeUtc, now),
                        textColor = ErrorColor,
                    )
                }
            }
        }
        if (health.storage.isNotEmpty()) {
            item { AdminSectionTitle("Storage") }
            item { AdminPanel { health.storage.forEach { StorageLine(it) } } }
        }
    }
}

@Composable
private fun StorageLine(row: StorageRow) {
    val fraction = row.fraction ?: return
    val colour = if (row.isWarning) WarningColor else SashimiAccent
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DotRow(
            dot = null,
            text = row.label,
            trailing = "${(fraction * 100).roundToInt()}% · ${formatBytes(row.totalBytes - row.usedBytes)} free",
            textColor = if (row.isWarning) WarningColor else androidx.compose.ui.graphics.Color.Unspecified,
        )
        LinearProgressIndicator(
            progress = { fraction.toFloat() },
            color = colour,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}
