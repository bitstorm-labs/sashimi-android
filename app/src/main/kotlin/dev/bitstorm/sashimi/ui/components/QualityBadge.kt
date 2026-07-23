package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bitstorm.sashimi.R
import dev.bitstorm.sashimi.ui.theme.SashimiBadge

/**
 * Resolution chip on cover art. Top tier ("4K") glows Jellyfin purple; "HD"/"SD"
 * sit on dark translucent slate. Port of Shared/Views/QualityBadge.swift.
 */
@Composable
fun QualityBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    val isTopTier = label == "4K"
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            modifier
                .clip(RoundedCornerShape(5.dp))
                .background(if (isTopTier) SashimiBadge else Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * TMDb community-rating pill for cover art. Mirrors [QualityBadge]'s look —
 * rounded, dark translucent slate — but pairs the TMDb wordmark with the score
 * to one decimal (e.g. "8.0"). Gated by showReviewRatings; sits lower-left.
 */
@Composable
fun ReviewRatingBadge(
    rating: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.tmdb_logo),
            contentDescription = "TMDb",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(9.dp),
        )
        Text(
            text = "%.1f".format(rating),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
