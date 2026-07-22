package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
