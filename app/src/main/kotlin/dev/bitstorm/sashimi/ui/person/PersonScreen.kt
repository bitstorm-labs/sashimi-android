package dev.bitstorm.sashimi.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.PersonInfo
import dev.bitstorm.sashimi.core.person.ServerMediaGroup
import dev.bitstorm.sashimi.core.person.ServerMediaResult
import dev.bitstorm.sashimi.core.person.displayRole
import dev.bitstorm.sashimi.core.session.ServerConfig
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.components.LocalShowQualityBadges
import dev.bitstorm.sashimi.ui.components.LocalShowReviewRatings
import dev.bitstorm.sashimi.ui.components.QualityBadge
import dev.bitstorm.sashimi.ui.components.ReviewRatingBadge
import dev.bitstorm.sashimi.ui.nav.PersonRoute
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.ImageUrlBuilder
import dev.bitstorm.sashimi.ui.util.ImageUrls

/**
 * A cast or crew member: portrait, name, role, and what else they are in across
 * every saved server. Titles found on several servers appear once with a pill
 * per server; tapping one of those asks which server to open it from. Port of
 * sashimi-apple PersonDetailView (#379).
 */
@Composable
fun PersonScreen(
    route: PersonRoute,
    onBack: () -> Unit,
    onOpenSource: (ServerMediaResult) -> Unit,
) {
    val vm: PersonViewModel =
        viewModel(key = "person-${route.originServerId}-${route.personId}", factory = PersonViewModel.Factory(route))
    val state by vm.state.collectAsStateWithLifecycle()
    val servers by ServiceLocator.session.servers.collectAsStateWithLifecycle()
    val multiServer = servers.size > 1
    var pickerGroup by remember { mutableStateOf<ServerMediaGroup?>(null) }

    val onSelect: (ServerMediaGroup) -> Unit = { group ->
        if (group.sources.size == 1) onOpenSource(group.primary) else pickerGroup = group
    }

    Scaffold(topBar = { BackTopBar(title = vm.person.name, onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") { PersonHeader(route) }
            item(key = "heading") {
                Text(
                    if (multiServer) "Filmography Across Servers" else "Filmography",
                    color = SashimiTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            when (val s = state) {
                FilmographyState.Loading ->
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                            CircularProgressIndicator(color = SashimiAccent)
                        }
                    }
                FilmographyState.Offline ->
                    item(key = "offline") {
                        Message(
                            title = "Filmography Unavailable",
                            body = "Connect to your Jellyfin server to see this person's other titles.",
                            onRetry = vm::load,
                        )
                    }
                is FilmographyState.Failed ->
                    item(key = "failed") {
                        Message(title = "Couldn't Load Filmography", body = s.message, onRetry = vm::load)
                    }
                is FilmographyState.Loaded -> {
                    if (s.failedServerCount > 0) {
                        item(key = "partial") {
                            val noun = if (s.failedServerCount == 1) "server" else "servers"
                            Text(
                                "${s.failedServerCount} $noun couldn't be reached, so this list may be incomplete.",
                                color = SashimiTextTertiary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    if (s.groups.isEmpty()) {
                        item(key = "empty") {
                            Message(
                                title = "No Other Movies or Shows",
                                body = "Nothing else on your servers credits this person.",
                                onRetry = null,
                            )
                        }
                    }
                    items(s.groups, key = { it.key }) { group ->
                        FilmographyRow(group, servers, showServerPills = multiServer, onClick = { onSelect(group) })
                    }
                }
            }
        }
    }

    pickerGroup?.let { group ->
        SourcePicker(
            group = group,
            servers = servers,
            onDismiss = { pickerGroup = null },
            onPick = { source ->
                pickerGroup = null
                onOpenSource(source)
            },
        )
    }
}

@Composable
private fun PersonHeader(route: PersonRoute) {
    // The person id only resolves on the server the cast row came from.
    val images =
        remember(route.originServerId) {
            route.originServerId?.let { ServiceLocator.clientForServer(it) }?.let { c -> ImageUrlBuilder { c } } ?: ImageUrls
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(SashimiCard), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = SashimiTextTertiary, modifier = Modifier.size(40.dp))
            if (route.primaryImageTag != null) {
                AsyncImage(
                    model = images.person(route.personId),
                    contentDescription = route.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(route.name, color = SashimiTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            val role = PersonInfo(route.personId, route.name, route.role, route.type).displayRole
            Text(role ?: "Cast & Crew", color = SashimiTextSecondary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Message(
    title: String,
    body: String,
    onRetry: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = SashimiTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = SashimiTextSecondary, fontSize = 14.sp)
        onRetry?.let { OutlinedButton(onClick = it) { Text("Try Again") } }
    }
}

@Composable
private fun FilmographyRow(
    group: ServerMediaGroup,
    servers: List<ServerConfig>,
    showServerPills: Boolean,
    onClick: () -> Unit,
) {
    val source = group.primary
    val item = source.item
    // Artwork comes from the server this copy lives on; its id means nothing elsewhere.
    val images =
        remember(source.serverId) {
            ServiceLocator.clientForServer(source.serverId)?.let { c -> ImageUrlBuilder { c } }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SashimiCard)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.3f))) {
            images?.primary(item.id, 168)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.displayTitle,
                color = SashimiTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetadataLine(item)
            if (showServerPills) ServerPills(group.sources, servers)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SashimiTextTertiary)
    }
}

@Composable
private fun MetadataLine(item: BaseItemDto) {
    val showQuality = LocalShowQualityBadges.current
    val showRatings = LocalShowReviewRatings.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val parts =
            listOfNotNull(
                item.displayYear?.toString(),
                when (item.type) {
                    ItemType.SERIES -> "Show"
                    ItemType.MOVIE -> "Movie"
                    else -> null
                },
                item.officialRating,
            )
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = SashimiTextSecondary, fontSize = 12.sp)
        val rating = item.communityRating ?: 0.0
        if (showRatings && rating > 0) ReviewRatingBadge(rating)
        if (showQuality) item.qualityBadge?.let { QualityBadge(it) }
    }
}

/** Current server names, so a renamed server reads correctly without a reload. */
private fun serverName(
    source: ServerMediaResult,
    servers: List<ServerConfig>,
): String = servers.firstOrNull { it.id == source.serverId }?.name ?: source.serverName

@Composable
private fun ServerPills(
    sources: List<ServerMediaResult>,
    servers: List<ServerConfig>,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sources.forEach { source ->
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SashimiAccent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(
                    serverName(source, servers),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 160.dp),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Choose a Server" for a title that exists on more than one. */
@Composable
private fun SourcePicker(
    group: ServerMediaGroup,
    servers: List<ServerConfig>,
    onDismiss: () -> Unit,
    onPick: (ServerMediaResult) -> Unit,
) {
    val showQuality = LocalShowQualityBadges.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.primary.item.displayTitle, color = SashimiTextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Select where you want to open this title.", color = SashimiTextSecondary, fontSize = 13.sp)
                group.sources.forEach { source ->
                    val name = serverName(source, servers)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SashimiCard)
                                .clickable(role = Role.Button, onClickLabel = "Open on $name") { onPick(source) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Dns, contentDescription = null, tint = SashimiAccent, modifier = Modifier.size(18.dp))
                        Text(name, color = SashimiTextPrimary, modifier = Modifier.weight(1f))
                        if (showQuality) source.item.qualityBadge?.let { QualityBadge(it) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
