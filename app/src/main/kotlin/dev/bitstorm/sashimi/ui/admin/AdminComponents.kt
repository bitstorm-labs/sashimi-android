package dev.bitstorm.sashimi.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bitstorm.sashimi.core.admin.Pill
import dev.bitstorm.sashimi.core.admin.PillKind
import dev.bitstorm.sashimi.core.admin.PlayMode
import dev.bitstorm.sashimi.core.admin.StreamState
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary

// Badge colours: green = direct, blue = audio converted, amber = video converted.
internal val DirectFg = Color(0xFF7BD389)
internal val DirectBg = Color(0xFF1F3A2C)
internal val AudioFg = Color(0xFF8CC2FF)
internal val AudioBg = Color(0xFF1E2E45)
internal val VideoFg = Color(0xFFF2B84B)
internal val VideoBg = Color(0xFF43341A)
internal val ErrorColor = Color(0xFFFF6B6B)
internal val WarningColor = VideoFg
internal val IdleGrey = Color(0xFF6B7684)
internal val AvatarBg = Color(0xFF3B4452)

/** The poster's state dot: green playing, amber paused, grey unknown. */
internal fun stateColor(state: StreamState): Color =
    when (state) {
        StreamState.PLAYING -> DirectFg
        StreamState.PAUSED -> VideoFg
        StreamState.OTHER -> IdleGrey
    }

/** One stream pill, in the OpsDec colours shared with Roku; the mode pill takes the badge's. */
@Composable
internal fun StreamPill(
    pill: Pill,
    mode: PlayMode,
) {
    val (fg, bg) =
        when (pill.kind) {
            PillKind.RESOLUTION -> Color(0xFF6CC6F0) to Color(0xFF15303F)
            PillKind.VIDEO -> Color(0xFFB79CF5) to Color(0xFF2A2140)
            PillKind.AUDIO -> Color(0xFFF2C46B) to Color(0xFF3A2D12)
            PillKind.BITRATE -> Color(0xFF5FD3C4) to Color(0xFF123331)
            PillKind.MODE -> badgeColors(mode)
        }
    Text(
        pill.text,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

internal fun badgeColors(mode: PlayMode): Pair<Color, Color> =
    when (mode) {
        PlayMode.DIRECT_PLAY -> DirectFg to DirectBg
        PlayMode.CONVERTING_AUDIO -> AudioFg to AudioBg
        PlayMode.CONVERTING_VIDEO -> VideoFg to VideoBg
    }

@Composable
internal fun PlayModeBadge(mode: PlayMode) {
    val (fg, bg) = badgeColors(mode)
    Text(
        mode.label,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun AdminSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = SashimiTextSecondary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun AdminPanel(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(16.dp),
                ).background(SashimiCard)
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) { content() }
}

/** One row with a coloured dot (null = no dot), text and a trailing time/value. */
@Composable
internal fun DotRow(
    dot: Color?,
    text: String,
    trailing: String,
    detail: String? = null,
    textColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SashimiTextSecondary) }
        }
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = SashimiTextSecondary)
    }
}

@Composable
internal fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = SashimiTextSecondary, style = MaterialTheme.typography.bodyLarge)
    }
}
