package dev.bitstorm.sashimi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.session.ServerConfig
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

private val ResumeThresholdOptions =
    linkedMapOf(
        "Always (0s)" to 0,
        "10 seconds" to 10,
        "30 seconds" to 30,
        "1 minute" to 60,
        "2 minutes" to 120,
        "5 minutes" to 300,
    )

// Empty string = "Device default" (no preference).
private val LanguageOptions =
    linkedMapOf(
        "Device default" to "",
        "English" to "en",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Italian" to "it",
        "Portuguese" to "pt",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Chinese" to "zh",
    )

/**
 * Settings: the real Servers section plus the playback preferences that M3's
 * player now honours (max bitrate, auto-play next, auto-skip intro/credits,
 * force direct play, resume threshold, audio/subtitle languages) and the quality
 * badge toggle.
 */
@Composable
fun SettingsScreen(
    session: SessionManager,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers by session.servers.collectAsStateWithLifecycle()
    val activeId by session.activeServerId.collectAsStateWithLifecycle()
    val settings = ServiceLocator.appSettings

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        item { SectionHeader("SERVERS") }

        items(servers, key = { it.id }) { server ->
            ServerRow(
                server = server,
                isActive = server.id == activeId,
                onSwitch = { session.switchServer(server.id) },
                onRemove = { session.removeServer(server.id) },
            )
        }

        item {
            OutlinedButton(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add Server", modifier = Modifier.padding(start = 8.dp))
            }
        }

        item { SectionHeader("PLAYBACK") }
        item { PlaybackSettingsSection(settings) }

        item { SectionHeader("GENERAL") }
        item {
            val showBadges by settings.showQualityBadges.collectAsStateWithLifecycle()
            SwitchRow("Show quality badges", showBadges, settings::setShowQualityBadges)
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            OutlinedButton(
                onClick = { session.logout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign Out")
            }
        }
    }
}

@Composable
private fun PlaybackSettingsSection(settings: AppSettings) {
    val maxBitrate by settings.maxBitrate.collectAsStateWithLifecycle()
    val autoPlayNext by settings.autoPlayNextEpisode.collectAsStateWithLifecycle()
    val autoSkipIntro by settings.autoSkipIntro.collectAsStateWithLifecycle()
    val autoSkipCredits by settings.autoSkipCredits.collectAsStateWithLifecycle()
    val forceDirectPlay by settings.forceDirectPlay.collectAsStateWithLifecycle()
    val resumeThreshold by settings.resumeThresholdSeconds.collectAsStateWithLifecycle()
    val subtitlesEnabled by settings.subtitlesEnabled.collectAsStateWithLifecycle()
    val audioLang by settings.preferredAudioLanguage.collectAsStateWithLifecycle()
    val subLang by settings.preferredSubtitleLanguage.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DropdownRow("Maximum bitrate", AppSettings.MAX_BITRATE_OPTIONS, maxBitrate, settings::setMaxBitrate)
        SwitchRow("Auto-play next episode", autoPlayNext, settings::setAutoPlayNextEpisode)
        SwitchRow("Auto-skip intro", autoSkipIntro, settings::setAutoSkipIntro)
        SwitchRow("Auto-skip credits", autoSkipCredits, settings::setAutoSkipCredits)
        SwitchRow(
            "Force direct play",
            forceDirectPlay,
            settings::setForceDirectPlay,
            subtitle = "Prefer the original file; suppress transcoding.",
        )
        DropdownRow("Resume threshold", ResumeThresholdOptions, resumeThreshold, settings::setResumeThresholdSeconds)
        SwitchRow("Subtitles on by default", subtitlesEnabled, settings::setSubtitlesEnabled)
        DropdownRow("Preferred audio language", LanguageOptions, audioLang, settings::setPreferredAudioLanguage)
        DropdownRow("Preferred subtitle language", LanguageOptions, subLang, settings::setPreferredSubtitleLanguage)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = SashimiTextTertiary)
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SashimiTextSecondary) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> DropdownRow(
    label: String,
    options: Map<String, T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.entries.firstOrNull { it.value == selected }?.key ?: options.keys.first()
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(selectedLabel, color = SashimiAccent, style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optLabel, value) ->
                DropdownMenuItem(
                    text = { Text(optLabel) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSwitch),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${server.username} · ${server.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SashimiTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isActive) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Active server",
                    tint = SashimiAccent,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove server")
            }
        }
    }
}
