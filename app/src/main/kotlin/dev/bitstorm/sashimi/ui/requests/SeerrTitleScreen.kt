package dev.bitstorm.sashimi.ui.requests

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrImages
import dev.bitstorm.sashimi.core.seerr.SeerrSeasonPlan
import dev.bitstorm.sashimi.core.seerr.SeerrStatusLabel
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import dev.bitstorm.sashimi.core.seerr.SeerrTitle
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiBackground
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * A Seerr title's request page (Apple mockup C): the backdrop, poster and
 * facts, then the main button: Request (a movie, one press), Request
 * seasons (the season picker), or a status line once asked for. An
 * available title offers its library page.
 */
@Composable
fun SeerrTitleScreen(
    target: SeerrTarget,
    onBack: () -> Unit,
    onOpenLibraryItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SeerrTitleViewModel =
        viewModel(key = "seerr-${target.id}", factory = viewModelFactory { initializer { SeerrTitleViewModel(target) } })
    val state by vm.model.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<SeerrSeasonPlan?>(null) }
    val context = LocalContext.current
    val details = state.details

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                SeerrImages.backdrop(details?.backdropPath)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, SashimiBackground))))
            }
            Column(
                Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier
                            .width(110.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SashimiCard),
                    ) {
                        SeerrImages.poster(details?.posterPath ?: target.posterPath, width = 342)?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = target.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(details?.title ?: target.title, style = MaterialTheme.typography.headlineSmall, color = SashimiTextPrimary)
                        val facts =
                            listOfNotNull(
                                details?.year?.toString(),
                                details?.contentRating,
                                details?.seriesStatus,
                            ).joinToString(" · ")
                        if (facts.isNotEmpty()) Text(facts, color = SashimiTextSecondary)
                        if (!details?.genres.isNullOrEmpty()) Text(details.genres.joinToString(", "), color = SashimiTextTertiary)
                        // No "Request" pill over a Request button: only a state worth saying.
                        if (details != null &&
                            vm.model.badge != SeerrBadge.REQUEST
                        ) {
                            StatusPill(SeerrStatusLabel(vm.model.badge.label, vm.model.badge))
                        }
                    }
                }
                ActionArea(state, onRequestMovie = vm::requestMovie, onChooseSeasons = { picking = vm.model.seasonPlan() })
                state.quotaNote?.let { Text(it, color = SashimiTextTertiary, style = MaterialTheme.typography.bodySmall) }
                state.errorMessage?.let { Text(it, color = SeerrBadge.DECLINED.color()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.libraryItem?.let { item ->
                        Button(
                            onClick = { onOpenLibraryItem(item.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = SeerrBadge.AVAILABLE.color()),
                        ) {
                            Text("Open in Library")
                        }
                    }
                    details?.trailerKey?.let { key ->
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$key".toUri()),
                                )
                            }
                        }) { Text("Trailer") }
                    }
                }
                details?.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = SashimiTextSecondary) }
                if (!details?.cast.isNullOrEmpty()) {
                    Text("Cast", style = MaterialTheme.typography.titleMedium, color = SashimiTextPrimary)
                    Text(details.cast.take(10).joinToString(", ") { it.name }, color = SashimiTextTertiary)
                }
                Box(Modifier.height(24.dp))
            }
        }
        BackTopBar(title = "", onBack = onBack, containerColor = Color.Transparent)
    }

    picking?.let { plan ->
        SeasonPickerDialog(
            initial = plan,
            title = details?.title ?: target.title,
            onDismiss = { picking = null },
            onRequest = {
                vm.requestSeasons(it)
                picking = null
            },
        )
    }
}

@Composable
private fun ActionArea(
    state: SeerrTitle.State,
    onRequestMovie: () -> Unit,
    onChooseSeasons: () -> Unit,
) {
    when (val action = state.action) {
        SeerrTitle.Action.Loading -> CircularProgressIndicator(color = SashimiAccent, modifier = Modifier.size(28.dp))
        SeerrTitle.Action.Request ->
            Button(onClick = onRequestMovie, enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isSubmitting) "Requesting…" else "Request")
            }
        SeerrTitle.Action.ChooseSeasons ->
            Button(onClick = onChooseSeasons, enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isSubmitting) "Requesting…" else "Request Seasons")
            }
        is SeerrTitle.Action.Status -> Text(action.text, color = SashimiTextSecondary, style = MaterialTheme.typography.titleMedium)
        is SeerrTitle.Action.Failed -> Text(action.text, color = SashimiTextTertiary)
    }
}

/** The season picker (Apple mockup D): missing seasons ticked, the server's seasons listed but locked. */
@Composable
private fun SeasonPickerDialog(
    initial: SeerrSeasonPlan,
    title: String,
    onDismiss: () -> Unit,
    onRequest: (SeerrSeasonPlan) -> Unit,
) {
    var plan by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request $title") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (plan.missing.size > 1 && plan.partialRequestsEnabled) {
                    SeasonLine(
                        title = "All missing seasons",
                        detail = "",
                        checked = plan.allMissingSelected,
                        enabled = true,
                        onToggle = { plan = plan.setAllMissing(!plan.allMissingSelected) },
                    )
                }
                plan.rows.forEach { row ->
                    SeasonLine(
                        title = row.title,
                        detail = row.detail,
                        // Seasons on the server read as had, not as unticked.
                        checked = row.id.startsWith("server-") || row.seasons.all { it in plan.selected },
                        enabled = row.isPickable,
                        onToggle = { row.seasons.forEach { plan = plan.toggle(it) } },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRequest(plan) }, enabled = plan.requestSeasons.isNotEmpty()) { Text(plan.buttonTitle) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SeasonLine(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) SashimiTextPrimary else SashimiTextTertiary)
            if (detail.isNotEmpty()) Text(detail, color = SashimiTextTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
