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
     * Which length rule a screen's trailer follows. Seasons and episodes play
     * their series' trailer, so they follow the series rule too.
     */
    fun trailerKind(item: BaseItemDto): HeroTrailerKind? =
        when (item.type) {
            ItemType.MOVIE -> HeroTrailerKind.MOVIE
            ItemType.SERIES, ItemType.SEASON, ItemType.EPISODE -> HeroTrailerKind.SERIES
            else -> null
        }

    /**
     * The part of a trailer the hero plays.
     *
     * The first seconds of a full trailer are almost always a rating card or a
     * studio logo, so they are skipped; a short clip has no such opening and
     * plays from the start.
     *
     * A movie plays to the trailer's own end (or, when the length is unknown,
     * until the item itself ends). A series plays a [HeroTrailerClip.LENGTH]
     * clip unless its theme song is heard during the trailer; see [clipEnd].
     * Nothing ever runs past the trailer's own end, so the fade always has a
     * moment of video left to fade.
     */
    fun clip(
        trailerSeconds: Double?,
        kind: HeroTrailerKind,
    ): HeroTrailerClip {
        val start =
            if (trailerSeconds != null && trailerSeconds >= HeroTrailerClip.MINIMUM_FOR_SKIP) HeroTrailerClip.OPENING_SKIP else 0.0
        return when (kind) {
            HeroTrailerKind.MOVIE -> HeroTrailerClip(start = start, end = trailerSeconds, trailerEnd = trailerSeconds)
            HeroTrailerKind.SERIES ->
                HeroTrailerClip(
                    start = start,
                    end = minOf(start + HeroTrailerClip.LENGTH, trailerSeconds ?: Double.POSITIVE_INFINITY),
                    trailerEnd = trailerSeconds,
                    followsTheme = true,
                )
        }
    }

    /**
     * Seconds into the trailer where [clip] ends given what its series' theme
     * song has done while the trailer ran, or null for "when the item ends".
     *
     * A theme heard during the trailer replaces the fixed clip: the trailer
     * runs while the theme does and ends with it, but always shows at least
     * [HeroTrailerClip.MINIMUM_WITH_THEME] seconds of video and never runs past
     * its own end. A movie ignores the theme.
     */
    fun clipEnd(
        clip: HeroTrailerClip,
        theme: HeroTrailerTheme,
    ): Double? {
        if (!clip.followsTheme) return clip.end
        return when (theme) {
            HeroTrailerTheme.None -> clip.end
            HeroTrailerTheme.Playing -> clip.trailerEnd
            is HeroTrailerTheme.Ended -> {
                val end = maxOf(theme.at, clip.start + HeroTrailerClip.MINIMUM_WITH_THEME)
                clip.trailerEnd?.let { minOf(end, it) } ?: end
            }
        }
    }
}

/** Which length rule a hero trailer follows. */
enum class HeroTrailerKind { MOVIE, SERIES }

/**
 * The series theme song as a running trailer sees it. Only a theme heard
 * while the trailer plays counts, and only its first ending.
 */
sealed interface HeroTrailerTheme {
    /** No theme has played during this trailer. */
    data object None : HeroTrailerTheme

    /** A theme is playing during this trailer. */
    data object Playing : HeroTrailerTheme

    /** The theme heard during this trailer ended (or began its end fade) [at] seconds into the trailer. */
    data class Ended(
        val at: Double,
    ) : HeroTrailerTheme

    /** A theme is playing now. */
    fun started(): HeroTrailerTheme = if (this == None) Playing else this

    /** The theme ended, or began its end fade, [at] seconds into the trailer. */
    fun ended(at: Double): HeroTrailerTheme = if (this == Playing) Ended(at) else this
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
    /** Where it stops with no theme in play; null = when the item ends. */
    val end: Double?,
    /** The trailer's own length, when the server knows it. */
    val trailerEnd: Double?,
    /** True when a theme heard during the trailer moves the end (series). */
    val followsTheme: Boolean = false,
) {
    companion object {
        const val OPENING_SKIP = 10.0

        /** A series clip with no theme in play. */
        const val LENGTH = 30.0
        const val MINIMUM_FOR_SKIP = 45.0

        /** The least video a series trailer shows when its theme ends early. */
        const val MINIMUM_WITH_THEME = 20.0
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
