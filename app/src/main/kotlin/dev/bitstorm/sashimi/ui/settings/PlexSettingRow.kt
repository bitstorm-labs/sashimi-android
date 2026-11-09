package dev.bitstorm.sashimi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.lists.PlexSetting
import dev.bitstorm.sashimi.core.lists.WatchlistPlexLinker
import dev.bitstorm.sashimi.core.lists.WatchlistPlexStatus
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.lists.ConnectPlexDialog
import dev.bitstorm.sashimi.ui.lists.listErrorMessage
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings › Plex: the Watchlist's Plex link, a second way to the same link as the Watchlist card. */
class PlexSettingViewModel(
    private val linker: WatchlistPlexLinker = ServiceLocator.watchlistLinker,
) : ViewModel() {
    private val status = WatchlistPlexStatus(ServiceLocator.watchlist, linker, viewModelScope)
    val setting: StateFlow<PlexSetting?> = status.setting

    private val _message = MutableStateFlow<String?>(null)

    /** A Disconnect that didn't go through. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Each time Settings shows: the server or user may have changed since. */
    fun refresh() {
        _message.value = null
        status.refresh()
    }

    fun disconnect() {
        _message.value = null
        viewModelScope.launch {
            runCatchingCancellable { linker.disconnect() }.onFailure { _message.value = listErrorMessage(it) }
        }
    }
}

/** The Plex row: hidden when the server has no Watchlist (404). */
@Composable
fun PlexSettingSection(sectionHeader: @Composable (String) -> Unit) {
    val vm: PlexSettingViewModel = viewModel()
    val setting by vm.setting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var connecting by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    val current = setting ?: return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sectionHeader("PLEX")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Plex", style = MaterialTheme.typography.bodyLarge)
            Text(
                when (current) {
                    PlexSetting.Loading -> "Checking…"
                    PlexSetting.NotConnected -> "Not connected"
                    is PlexSetting.Connected ->
                        if (current.needsReconnect) {
                            "Connected as ${current.username} · stopped syncing"
                        } else {
                            "Connected as ${current.username}"
                        }
                    PlexSetting.Unknown -> "Couldn't check right now"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Keeps your Watchlist in sync with your Plex watchlist.",
                style = MaterialTheme.typography.bodySmall,
                color = SashimiTextSecondary,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current) {
                    PlexSetting.Loading -> Unit
                    PlexSetting.NotConnected, PlexSetting.Unknown ->
                        OutlinedButton(onClick = { connecting = true }) { Text("Connect Plex") }
                    is PlexSetting.Connected -> {
                        if (current.needsReconnect) OutlinedButton(onClick = { connecting = true }) { Text("Reconnect Plex") }
                        TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
                    }
                }
            }
        }
    }

    if (connecting) {
        // The same flow as the Watchlist card; the linker's change signal refreshes this row.
        ConnectPlexDialog(onDismiss = { connecting = false }, onLinked = { connecting = false })
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Plex?") },
            text = { Text("Your Watchlist stays as it is. Changes stop syncing with Plex.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    vm.disconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}
