package dev.bitstorm.sashimi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.session.ServerConfig
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * Settings. M1 ships only the real Servers section (list / switch / remove /
 * add / sign out); the remaining sections arrive with the parity sweep (M5).
 */
@Composable
fun SettingsScreen(
    session: SessionManager,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers by session.servers.collectAsStateWithLifecycle()
    val activeId by session.activeServerId.collectAsStateWithLifecycle()

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
        item {
            Text(
                text = "SERVERS",
                style = MaterialTheme.typography.labelMedium,
                color = SashimiTextTertiary,
            )
        }

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

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            OutlinedButton(
                onClick = { session.logout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign Out")
            }
        }

        item {
            Text(
                text = "More settings coming in M5",
                style = MaterialTheme.typography.bodySmall,
                color = SashimiTextTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
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
