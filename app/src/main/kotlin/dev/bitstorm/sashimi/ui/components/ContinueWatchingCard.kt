package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.cleanedYouTubeTitle
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiLink
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import dev.bitstorm.sashimi.ui.util.Formatting
import dev.bitstorm.sashimi.ui.util.ImageUrls

private val ProgressTrack = Color(0xFF404040)

/**
 * 16:9 Continue Watching card. Port of MobileContinueWatchingCard: backdrop with
 * a bottom gradient carrying a play glyph + "Xm left", a progress bar, and a
 * title + "S#:E# - Episode" (or year) caption.
 */
@Composable
fun ContinueWatchingCard(
    item: BaseItemDto,
    width: Dp,
    modifier: Modifier = Modifier,
    libraryName: String? = null,
) {
    val isYt = isYouTubeItem(item, libraryName)

    Column(modifier = modifier.width(width)) {
        Box(
            modifier =
                Modifier
                    .width(width)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SashimiCard),
        ) {
            AsyncImage(
                model = ImageUrls.continueWatching(item, width.value.toInt()),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = SashimiLink,
                        modifier = Modifier.size(14.dp),
                    )
                    val total = item.runTimeTicks
                    if (total != null) {
                        Text(
                            text = Formatting.remaining(total, item.userData?.playbackPositionTicks ?: 0L),
                            color = SashimiTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(ProgressTrack),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(item.progressPercent.toFloat().coerceIn(0f, 1f))
                                .fillMaxSize()
                                .clip(RoundedCornerShape(50))
                                .background(SashimiAccent),
                    )
                }
            }
        }

        Text(
            text = displayTitle(item),
            color = SashimiTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val caption = episodeInfo(item, isYt) ?: yearText(item)
        if (caption != null) {
            Text(
                text = caption,
                color = SashimiTextTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun displayTitle(item: BaseItemDto): String =
    when (item.type) {
        ItemType.MOVIE, ItemType.VIDEO -> item.name
        ItemType.SERIES -> item.name.cleanedYouTubeTitle()
        ItemType.EPISODE -> (item.seriesName ?: item.name).cleanedYouTubeTitle()
        else -> item.name
    }

private fun episodeInfo(
    item: BaseItemDto,
    isYouTube: Boolean,
): String? {
    if (item.type != ItemType.EPISODE) return null
    if (isYouTube && item.premiereDate != null) {
        val date = Formatting.numericDate(item.premiereDate) ?: return item.name
        return "$date - ${item.name}"
    }
    val season = item.parentIndexNumber ?: 1
    val episode = item.indexNumber ?: 1
    return "S$season:E$episode - ${item.name}"
}

private fun yearText(item: BaseItemDto): String? {
    if (item.type == ItemType.EPISODE) return null
    return item.productionYear?.toString()
}
