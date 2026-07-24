package dev.bitstorm.sashimi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.downloads.StorageAccounting
import dev.bitstorm.sashimi.core.session.ServerConfig
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.downloads.formatBytes
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
    onOpenRowOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers by session.servers.collectAsStateWithLifecycle()
    val activeId by session.activeServerId.collectAsStateWithLifecycle()
    val settings = ServiceLocator.appSettings
    val downloads by ServiceLocator.downloadManager.downloads.collectAsStateWithLifecycle()

    var showSignOut by remember { mutableStateOf(false) }
    var showDeleteAll by remember { mutableStateOf(false) }

    // Tablet pass: keep the settings content at a comfortable reading width rather
    // than stretching controls the full width of an expanded window.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp),
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

            item { SectionHeader("HOME SCREEN") }
            item { NavRow("Customize Row Order", onClick = onOpenRowOrder) }

            item { SectionHeader("PLAYBACK") }
            item { PlaybackSettingsSection(settings) }

            item { SectionHeader("GENERAL") }
            item {
                val showBadges by settings.showQualityBadges.collectAsStateWithLifecycle()
                SwitchRow("Show quality badges", showBadges, settings::setShowQualityBadges)
                val showRatings by settings.showReviewRatings.collectAsStateWithLifecycle()
                SwitchRow("Show Review Ratings", showRatings, settings::setShowReviewRatings)
                val useEpisodeRatings by settings.useEpisodeRatings.collectAsStateWithLifecycle()
                SwitchRow("Use Episode Ratings", useEpisodeRatings, settings::setUseEpisodeRatings)
            }

            item { SectionHeader("DOWNLOADS") }
            item {
                val used = StorageAccounting.bytesUsed(downloads)
                val free = ServiceLocator.downloadManager.availableDiskSpace()
                InfoRow("Storage used", formatBytes(used))
                InfoRow("Available space", formatBytes(free))
                TextButton(
                    onClick = { showDeleteAll = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    Text("Delete All Downloads", color = Color.Red)
                }
            }

            item { SectionHeader("ABOUT") }
            item {
                val context = LocalContext.current
                val (versionName, versionCode) =
                    remember {
                        runCatching {
                            val info = context.packageManager.getPackageInfo(context.packageName, 0)
                            info.versionName to info.longVersionCode
                        }.getOrDefault("?" to 0L)
                    }
                InfoRow("Version", versionName ?: "?")
                InfoRow("Build", versionCode.toString())
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item {
                OutlinedButton(
                    onClick = { showSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign Out", color = Color.Red)
                }
            }
        }
    }

    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { showSignOut = false },
            title = { Text("Sign Out") },
            text = { Text("Sign out of this server? Downloaded content stays on your device.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOut = false
                    session.logout()
                }) { Text("Sign Out", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showSignOut = false }) { Text("Cancel") } },
        )
    }
    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Delete All Downloads?") },
            text = { Text("This will remove all downloaded files from your device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    ServiceLocator.downloadManager.deleteAll()
                }) { Text("Delete All", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NavRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SashimiTextTertiary)
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = SashimiTextSecondary, style = MaterialTheme.typography.bodyMedium)
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
            "Always play original",
            forceDirectPlay,
            settings::setForceDirectPlay,
            subtitle = "Play the untouched file; never convert quality.",
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
