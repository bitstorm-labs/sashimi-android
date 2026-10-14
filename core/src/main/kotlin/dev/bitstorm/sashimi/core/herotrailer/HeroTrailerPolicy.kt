package dev.bitstorm.sashimi.core.herotrailer

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType

/**
 * The pure rules behind the detail-screen hero trailer, ported from the Apple
 * client's `HeroTrailerPolicy` (1.6.44 to 1.6.47) and kept apart from the
 * ExoPlayer plumbing in `:app` so every decision is unit-testable.
 */
object HeroTrailerPolicy {
    /**
     * The item whose local trailer plays in this screen's hero, or null when the
     * screen never plays one.
     *
     * Seasons and episodes use their series' trailer: episodes have no trailers
     * of their own, and a clip cut from the episode itself would risk spoilers.
     * A movie or series the server says has no local trailer is skipped here,
     * without a request.
     */
    fun trailerSourceId(item: BaseItemDto): String? =
        when (item.type) {
            ItemType.MOVIE, ItemType.SERIES -> if (item.localTrailerCount == 0) null else item.id
            ItemType.SEASON, ItemType.EPISODE -> item.seriesId
            else -> null
        }

    /**
     * The "Play trailers on detail screens" setting as it applies right now.
     * Until the user picks a value it is off at compact width (a phone, or a
     * folded foldable) and on wherever the hero is large, the same split as
     * iPhone versus iPad.
     */
    fun settingOn(
        stored: Boolean?,
        isCompactWidth: Boolean,
    ): Boolean = stored ?: !isCompactWidth

    /**
     * Silent while a theme song plays (the Apple rule), and also while anything
     * else is playing, so decoration never talks over the user's own audio.
     */
    fun shouldMute(
        themeIsPlaying: Boolean,
        otherAudioPlaying: Boolean,
    ): Boolean = themeIsPlaying || otherAudioPlaying

    /**
     * The part of a trailer the hero plays.
     *
     * A full trailer runs about two minutes, far more than a background preview
     * should. The first seconds of a full trailer are almost always a rating
     * card or a studio logo, so they are skipped; a short clip has no such
     * opening and plays from the start. The end never runs past the trailer's
     * own end, so the fade always has a moment of video left to fade.
     */
    fun clip(trailerSeconds: Double?): HeroTrailerClip {
        if (trailerSeconds == null || trailerSeconds < HeroTrailerClip.MINIMUM_FOR_SKIP) {
            val end = minOf(HeroTrailerClip.LENGTH, trailerSeconds ?: Double.POSITIVE_INFINITY)
            return HeroTrailerClip(start = 0.0, end = end)
        }
        val start = HeroTrailerClip.OPENING_SKIP
        return HeroTrailerClip(start = start, end = start + HeroTrailerClip.LENGTH)
    }
}

/** What decides whether a trailer may start at all right now. */
data class HeroTrailerConditions(
    val settingOn: Boolean,
    val isOnline: Boolean,
    val isMetered: Boolean,
    /** The screen's pinned server, or null when it shows the active server. */
    val screenServerId: String?,
    val activeServerId: String?,
) {
    /**
     * Offline there is nothing to stream; on a metered network a 20 MB
     * background video nobody asked for is the wrong trade; and a screen pinned
     * to a server other than the active one is skipped, as on Apple.
     */
    val isEligible: Boolean
        get() {
            if (!settingOn || !isOnline || isMetered) return false
            return screenServerId == null || screenServerId == activeServerId
        }
}

/** Seconds into the trailer where the hero starts and stops it. */
data class HeroTrailerClip(
    val start: Double,
    val end: Double,
) {
    companion object {
        const val OPENING_SKIP = 10.0
        const val LENGTH = 30.0
        const val MINIMUM_FOR_SKIP = 45.0
    }
}

/** A title's trailer: its item id and, when the server knows it, its length. */
data class HeroTrailerSource(
    val id: String,
    val seconds: Double?,
) {
    companion object {
        /** The first of a title's local trailers, or null when it has none. */
        fun from(trailers: List<BaseItemDto>): HeroTrailerSource? {
            val first = trailers.firstOrNull() ?: return null
            return HeroTrailerSource(first.id, first.runTimeTicks?.let { it / TICKS_PER_SECOND })
        }

        private const val TICKS_PER_SECOND = 10_000_000.0
    }
}

/** A playable hero-trailer stream and the server session behind it. */
data class HeroTrailerStream(
    val url: String,
    /** Null when the server reported none; the encoding then ends on the server's idle timeout. */
    val playSessionId: String?,
)

/**
 * The hero trailer's transcode cap. A phone shows it full width at 220dp, so
 * 1280 / 4 Mbps is plenty; the wide (tablet) hero is about half a large screen
 * and looked soft at that, so it gets 1920 / 8 Mbps. Same caps as
 * sashimi-apple#680.
 */
data class HeroTrailerCaps(
    val maxBitrate: Int,
    val maxWidth: Int,
) {
    companion object {
        val COMPACT = HeroTrailerCaps(maxBitrate = 4_000_000, maxWidth = 1280)
        val EXPANDED = HeroTrailerCaps(maxBitrate = 8_000_000, maxWidth = 1920)

        fun forWidth(isCompactWidth: Boolean): HeroTrailerCaps = if (isCompactWidth) COMPACT else EXPANDED
    }
}
