package dev.bitstorm.sashimi.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.admin.AdminLoad
import dev.bitstorm.sashimi.core.admin.Attention
import dev.bitstorm.sashimi.core.admin.AttentionSeverity
import dev.bitstorm.sashimi.core.admin.HealthSnapshot
import dev.bitstorm.sashimi.core.admin.PlayModes
import dev.bitstorm.sashimi.core.admin.ServerSummary
import dev.bitstorm.sashimi.core.admin.SessionInfoDto
import dev.bitstorm.sashimi.core.admin.SessionTitles
import dev.bitstorm.sashimi.core.admin.StreamCards
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.ImageUrls
import dev.bitstorm.sashimi.ui.util.rememberImageWidth
import java.time.Instant

/** Keeps the last good value of a poll so a failed refresh doesn't blank the screen. */
@Composable
internal fun <T> rememberLastReady(load: AdminLoad<T>?): T? {
    var last by remember { mutableStateOf<T?>(null) }
    if (load is AdminLoad.Ready) last = load.data
    return last
}

/**
 * The admin Server screen, phone layout: Now playing, Needs attention, then
 * links to Activity and Health. Reached from Settings, and only shown there
 * for an administrator.
 */
@Composable
fun ServerDashboardScreen(
    onBack: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenHealth: () -> Unit,
    vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory()),
) {
    val sessionsLoad by vm.sessions.collectAsStateWithLifecycle()
    val healthLoad by vm.health.collectAsStateWithLifecycle()
    val forbidden by vm.forbidden.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val sessions = rememberLastReady(sessionsLoad)
    val health = rememberLastReady(healthLoad)

    LaunchedEffect(forbidden) { if (forbidden) onBack() }
    if (forbidden) return

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    var detailsFor by remember { mutableStateOf<SessionInfoDto?>(null) }
    var stopFor by remember { mutableStateOf<SessionInfoDto?>(null) }
    var messageFor by remember { mutableStateOf<SessionInfoDto?>(null) }

    Scaffold(
        topBar = { BackTopBar(title = "Dashboard", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            if (sessions == null && health == null) {
                if (sessionsLoad is AdminLoad.Failed) {
                    CenteredMessage("Can't reach the server. Retrying…")
                } else {
                    CircularProgressIndicator(Modifier.padding(48.dp), color = SashimiAccent)
                }
                return@Box
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(health, sessions, failing = sessionsLoad is AdminLoad.Failed) }
                item { AdminSectionTitle("Now playing") }
                val playing = sessions?.let(ServerSummary::playing).orEmpty()
                if (sessions != null && playing.isEmpty()) {
                    item { Text("Nobody's watching", color = SashimiTextSecondary) }
                }
                items(playing, key = { it.id }) { s ->
                    StreamCard(
                        session = s,
                        onDetails = { detailsFor = s },
                        onStop = { stopFor = s },
                        onMessage = { messageFor = s },
                    )
                }
                item { AdminSectionTitle("Needs attention") }
                item { NeedsAttention(health) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        LinkTile("Activity", onOpenActivity, Modifier.weight(1f))
                        LinkTile("Health", onOpenHealth, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    detailsFor?.let { s -> StreamDetailsSheet(s, onDismiss = { detailsFor = null }) }
    stopFor?.let { s ->
        AlertDialog(
            onDismissRequest = { stopFor = null },
            title = { Text("Stop this stream?") },
            text = {
                Text(
                    "${SessionTitles.title(
                        s.nowPlayingItem,
                    )} stops playing for ${s.userName ?: "this viewer"} on ${s.deviceName ?: "their device"}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.stop(s)
                    stopFor = null
                }) { Text("Stop", color = ErrorColor) }
            },
            dismissButton = { TextButton(onClick = { stopFor = null }) { Text("Cancel") } },
        )
    }
    messageFor?.let { s ->
        MessageDialog(
            session = s,
            onSend = {
                vm.sendMessage(s, it)
                messageFor = null
            },
            onDismiss = { messageFor = null },
        )
    }
}

@Composable
private fun Header(
    health: HealthSnapshot?,
    sessions: List<SessionInfoDto>?,
    failing: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val name = listOfNotNull(health?.info?.serverName, health?.info?.version).joinToString(" ")
        if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        sessions?.let { Text(ServerSummary.headline(ServerSummary.of(it)), color = SashimiTextSecondary) }
        if (failing) {
            Text(
                "Can't reach the server. Showing the last update.",
                color = WarningColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * One Now playing card, OpsDec style (design revision 2026-10-18) compacted
 * into the phone row: poster with a state dot, title with the series and an
 * S04E03 tag, avatar initial and user, app and device, progress, the pills
 * (wrapping), then the reason and Local/Remote. Every string comes from
 * [StreamCards], shared word for word with the Roku and Apple clients.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamCard(
    session: SessionInfoDto,
    onDetails: () -> Unit,
    onStop: () -> Unit,
    onMessage: () -> Unit,
) {
    val item = session.nowPlayingItem
    val title = StreamCards.title(item)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(16.dp),
                ).background(SashimiCard)
                .clickable(onClick = onDetails)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item != null) {
                AsyncImage(
                    model = ImageUrls.cardPoster(item, rememberImageWidth(44.dp, 66.dp, 2f / 3f)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .align(
                        Alignment.TopEnd,
                    ).padding(4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(stateColor(StreamCards.state(session))),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title.title.ifEmpty { SessionTitles.title(item) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (title.sub.isNotEmpty() || title.ep.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (title.sub.isNotEmpty()) {
                        Text(
                            title.sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = SashimiTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (title.ep.isNotEmpty()) {
                        Text(
                            title.ep,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SashimiAccent,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SashimiAccent.copy(alpha = 0.18f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(AvatarBg), contentAlignment = Alignment.Center) {
                    Text(
                        StreamCards.initialOf(session.userName),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Text(
                    listOf(session.userName.orEmpty(), StreamCards.deviceLine(session)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = SashimiTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val fraction = SessionTitles.progress(session.playState?.positionTicks, item?.runTimeTicks)
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = SashimiAccent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
            val position = SessionTitles.position(session.playState?.positionTicks, item?.runTimeTicks)
            val percent = StreamCards.percentText(session)
            if (position != null || percent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(position.orEmpty(), style = MaterialTheme.typography.labelSmall, color = SashimiTextSecondary)
                    Text(percent, style = MaterialTheme.typography.labelSmall, color = SashimiTextSecondary)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val mode = PlayModes.of(session.transcodingInfo)
                StreamCards.pills(session).forEach { StreamPill(it, mode) }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    StreamCards.footerReason(session),
                    style = MaterialTheme.typography.labelSmall,
                    color = SashimiTextTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(StreamCards.whereLabel(session), style = MaterialTheme.typography.labelSmall, color = SashimiTextTertiary)
            }
        }
        StreamMenu(session, onDetails, onStop, onMessage)
    }
}

@Composable
private fun StreamMenu(
    session: SessionInfoDto,
    onDetails: () -> Unit,
    onStop: () -> Unit,
    onMessage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Stream actions", tint = SashimiTextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Details") }, onClick = {
                open = false
                onDetails()
            })
            // The server can only deliver commands to a client that accepts them.
            if (session.supportsRemoteControl) {
                DropdownMenuItem(text = { Text("Send a message") }, onClick = {
                    open = false
                    onMessage()
                })
                DropdownMenuItem(text = { Text("Stop", color = ErrorColor) }, onClick = {
                    open = false
                    onStop()
                })
            }
        }
    }
}

@Composable
private fun NeedsAttention(health: HealthSnapshot?) {
    AdminPanel {
        if (health == null) {
            DotRow(dot = null, text = "Checking…", trailing = "")
            return@AdminPanel
        }
        val now = remember(health) { Instant.now() }
        val items = remember(health) { Attention.items(health.tasks, health.activity, health.storage, now) }
        if (items.isEmpty()) {
            DotRow(dot = DirectFg, text = "All clear", trailing = "")
        }
        items.forEach {
            DotRow(dot = if (it.severity == AttentionSeverity.ERROR) ErrorColor else WarningColor, text = it.text, trailing = it.`when`)
        }
    }
}

@Composable
private fun LinkTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SashimiCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageDialog(
    session: SessionInfoDto,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message ${session.userName ?: "viewer"}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MESSAGE_MAX) },
                placeholder = { Text("Shown on their screen") },
                supportingText = { Text("${text.length}/$MESSAGE_MAX") },
            )
        },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MESSAGE_MAX = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamDetailsSheet(
    session: SessionInfoDto,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SashimiCard) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SessionTitles.title(session.nowPlayingItem), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SessionTitles.episodeLine(session.nowPlayingItem)?.let { Text(it, color = SashimiTextSecondary) }
            PlayModeBadge(PlayModes.of(session.transcodingInfo))
            SessionTitles.details(session).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(label, color = SashimiTextSecondary, modifier = Modifier.width(120.dp))
                    Text(value, modifier = Modifier.weight(1f))
                }
            }
            Box(Modifier.height(24.dp))
        }
    }
}
