package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.model.MediaSegmentType
import kotlin.math.ceil

/**
 * What the Skip Intro / Skip Credits countdown pill shows for a segment at a
 * position: how much of the segment has played (the fill), the time left, and
 * the words. Same design on Apple and Roku. Positions are absolute item seconds,
 * like the segments themselves.
 */
object SkipCountdown {
    /** Fraction of [segment] already played, 0 at its start to 1 at its end. */
    fun fraction(
        segment: MediaSegmentDto,
        positionSeconds: Double,
    ): Float {
        val length = segment.endSeconds - segment.startSeconds
        if (length <= 0.0) return 1f
        return ((positionSeconds - segment.startSeconds) / length).toFloat().coerceIn(0f, 1f)
    }

    /** Whole seconds left in [segment], rounded up so "0:00" only shows at the very end. */
    fun secondsLeft(
        segment: MediaSegmentDto,
        positionSeconds: Double,
    ): Int = ceil((segment.endSeconds - positionSeconds).coerceAtLeast(0.0)).toInt()

    /** "0:24", "1:05", "12:00". */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun label(type: MediaSegmentType): String =
        when (type) {
            MediaSegmentType.INTRO -> "Skip Intro"
            MediaSegmentType.OUTRO -> "Skip Credits"
            MediaSegmentType.RECAP -> "Skip Recap"
            MediaSegmentType.PREVIEW -> "Skip Preview"
            else -> "Skip"
        }

    /** Spoken description: "Skip Intro, 24 seconds left". */
    fun contentDescription(
        type: MediaSegmentType,
        secondsLeft: Int,
    ): String {
        val s = secondsLeft.coerceAtLeast(0)
        return "${label(type)}, $s ${if (s == 1) "second" else "seconds"} left"
    }
}
