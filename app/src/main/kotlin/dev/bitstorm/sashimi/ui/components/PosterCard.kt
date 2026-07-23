package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.cleanedYouTubeTitle
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.util.ImageUrls

private val WatchedGreen = Color(red = 0.29f, green = 0.73f, blue = 0.47f)
private val NewBadgeBlue = Color(red = 0.29f, green = 0.55f, blue = 0.73f)

/**
 * Poster card shared by Recently Added, See All, Library grid, and Search. Port
 * of MobileRecentlyAddedCard: portrait (or circular for YouTube series) cover
 * with a watched check, optional "X new" badge, quality badge (gated by
 * showQualityBadges), and a single-line title that scales with cover size.
 */
@Composable
fun PosterCard(
    item: BaseItemDto,
    width: Dp,
    modifier: Modifier = Modifier,
    libraryName: String? = null,
    isCircular: Boolean = false,
    badgeCount: Int? = null,
) {
    val showQualityBadges = LocalShowQualityBadges.current
    val showReviewRatings = LocalShowReviewRatings.current
    val shape = if (isCircular) CircleShape else RoundedCornerShape(8.dp)
    // Title font grows with the cover so bigger grids read better (Swift: 13–16).
    val titleSize = (width.value * 0.095f).coerceIn(13f, 16f)

    Column(
        modifier = modifier.width(width),
        horizontalAlignment = if (isCircular) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // The cover art is clipped to [shape]; badges live in this OUTER, unclipped
        // Box so a circular card's checkmark/"X new" badge at the corner isn't cut
        // off by the circle mask (matches the iOS ZStack, which clips only the art).
        Box(
            modifier =
                Modifier
                    .width(width)
                    .then(if (isCircular) Modifier.aspectRatio(1f) else Modifier.aspectRatio(2f / 3f)),
        ) {
            AsyncImage(
                model = ImageUrls.cardPoster(item, width.value.toInt()),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape).background(SashimiCard),
            )

            if (item.userData?.played == true) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Watched",
                    tint = WatchedGreen,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                )
            } else if (badgeCount != null && badgeCount >= 1) {
                Text(
                    text = "$badgeCount new",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NewBadgeBlue)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            val quality = item.qualityBadge
            if (showQualityBadges && !isCircular && quality != null) {
                QualityBadge(
                    label = quality,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }

            val rating = item.communityRating ?: 0.0
            if (showReviewRatings && !isCircular && rating > 0) {
                ReviewRatingBadge(
                    rating = rating,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
            }
        }

        Text(
            text = cardTitle(item),
            color = SashimiTextPrimary,
            fontSize = titleSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(width).padding(top = 4.dp),
        )
    }
}

private fun cardTitle(item: BaseItemDto): String =
    when (item.type) {
        ItemType.MOVIE -> item.name
        ItemType.SERIES -> item.name.cleanedYouTubeTitle()
        ItemType.EPISODE -> (item.seriesName ?: item.name).cleanedYouTubeTitle()
        else -> item.name
    }
