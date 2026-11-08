package dev.bitstorm.sashimi.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrImages
import dev.bitstorm.sashimi.core.seerr.SeerrRequestRow
import dev.bitstorm.sashimi.core.seerr.SeerrRequestState
import dev.bitstorm.sashimi.core.seerr.SeerrStatusLabel
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/** The pill colours, one family per tone (Apple SeerrBadge.color). */
fun SeerrBadge.color(): Color =
    when (this) {
        SeerrBadge.REQUEST -> SashimiAccent
        SeerrBadge.PENDING -> Color(0xFFE0A030)
        SeerrBadge.COMING -> Color(0xFF5CADE6)
        SeerrBadge.DECLINED -> Color(0xFFD9534F)
        SeerrBadge.AVAILABLE -> Color(0xFF3FB27F)
    }

/** A Home-row request state as a pill. */
fun SeerrRequestState.pill(): SeerrStatusLabel =
    when (this) {
        SeerrRequestState.Pending -> SeerrStatusLabel(label, SeerrBadge.PENDING)
        is SeerrRequestState.Coming -> SeerrStatusLabel(label, SeerrBadge.COMING)
        SeerrRequestState.Ready -> SeerrStatusLabel(label, SeerrBadge.AVAILABLE)
        SeerrRequestState.Declined, SeerrRequestState.Failed -> SeerrStatusLabel(label, SeerrBadge.DECLINED)
    }

@Composable
fun StatusPill(
    label: SeerrStatusLabel,
    modifier: Modifier = Modifier,
) {
    val color = label.tone.color()
    Text(
        label.label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.9f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A TMDB poster with an optional pill in its corner, the title and up to two lines under it. */
@Composable
fun SeerrPosterCard(
    title: String,
    posterPath: String?,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pill: SeerrStatusLabel? = null,
    subtitle: String? = null,
    caption: String? = null,
    progress: Double? = null,
) {
    Column(modifier.width(width).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(SashimiCard),
        ) {
            val url = SeerrImages.poster(posterPath, width = 342)
            if (url != null) {
                AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = SashimiTextTertiary, modifier = Modifier.align(Alignment.Center))
            }
            pill?.let { StatusPill(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    color = SeerrBadge.COMING.color(),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = SashimiTextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = SashimiTextTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        caption?.let { Text(it, color = SashimiTextTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A titled horizontal row of posters, used by Home, Discover and Search. */
@Composable
fun <T> SeerrPosterRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 120.dp,
    /** Zero inside a grid that already pads its content. */
    horizontalPadding: Dp = 16.dp,
    onItemShown: ((T) -> Unit)? = null,
    card: @Composable (T, Dp) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = SashimiTextPrimary,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = key) { item ->
                if (onItemShown != null) LaunchedEffect(key(item)) { onItemShown(item) }
                card(item, cardWidth)
            }
        }
    }
}

/**
 * Home's requests row: "Recent Requests" (everyone's, with who asked) or
 * "Your Requests". Draws nothing without a Seerr account or anything to show.
 */
@Composable
fun SeerrHomeRow(
    vm: HomeRequestsViewModel,
    onOpenTarget: (SeerrTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by vm.model.rows.collectAsStateWithLifecycle()
    val shown = remember(rows) { vm.model.homeRows() }
    if (shown.isEmpty()) return
    SeerrPosterRow(title = vm.model.title, items = shown, key = { it.id }, modifier = modifier) { row: SeerrRequestRow, width ->
        SeerrPosterCard(
            title = row.title,
            posterPath = row.posterPath,
            width = width,
            onClick = { onOpenTarget(row.target) },
            pill = row.state.pill(),
            subtitle = row.subtitle,
            caption = vm.model.requester(row),
            progress = (row.state as? SeerrRequestState.Coming)?.progress,
        )
    }
}
