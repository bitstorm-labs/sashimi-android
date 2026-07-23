package dev.bitstorm.sashimi.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bitstorm.sashimi.core.model.BaseItemDto
import kotlin.math.max

/**
 * Whether the "showQualityBadges" setting is on. Provided at the app root from
 * [dev.bitstorm.sashimi.core.settings.AppSettings] so any card can gate its
 * resolution chip without threading the flag through every call. Port of the
 * Swift @AppStorage("showQualityBadges").
 */
val LocalShowQualityBadges = compositionLocalOf { true }

/**
 * Whether the "showReviewRatings" setting is on. Provided at the app root from
 * [dev.bitstorm.sashimi.core.settings.AppSettings] so any card can gate its
 * TMDb community-rating pill without threading the flag through every call.
 * Mirrors [LocalShowQualityBadges].
 */
val LocalShowReviewRatings = compositionLocalOf { true }

/**
 * Whether the "Use Episode Ratings" setting is on. When off (the default), a
 * card for a TV episode shows no review-rating pill — an episode's
 * `communityRating` is that episode's score, not the show's overall rating, and
 * the poster card has no series-level rating to fall back on. When on, episode
 * cards may show their own rating. Series/movie cards are unaffected: their
 * `communityRating` already IS the overall rating.
 */
val LocalUseEpisodeRatings = compositionLocalOf { false }

/** Poster-grid layout metrics — column count + exact per-card width. */
data class GridMetrics(
    val columns: Int,
    val cardWidth: Dp,
)

/**
 * Derives the grid column count from available width so covers fill their
 * column (no fixed-width gaps). Port of the identical `gridMetrics` in
 * MobileLibraryBrowseView / MobileSearchView: target 118dp compact / 165dp
 * expanded, minimum 2 columns.
 */
fun gridMetrics(
    availableWidth: Dp,
    isCompact: Boolean,
    spacing: Dp = 16.dp,
): GridMetrics {
    val avail = availableWidth - spacing * 2
    val target = if (isCompact) 118.dp else 165.dp
    val count = max(2, ((avail + spacing) / (target + spacing)).toInt())
    val cardWidth = (avail - spacing * (count - 1)) / count
    return GridMetrics(count, cardWidth)
}

/** YouTube-library detection by name or path. Port of the Swift `isYouTube` checks. */
fun isYouTube(
    libraryName: String?,
    path: String? = null,
): Boolean {
    if (libraryName?.contains("youtube", ignoreCase = true) == true) return true
    return path?.contains("youtube", ignoreCase = true) == true
}

fun isYouTubeItem(
    item: BaseItemDto,
    libraryName: String?,
): Boolean = isYouTube(libraryName, item.path)
