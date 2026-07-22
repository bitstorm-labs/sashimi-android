package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.model.MediaSegmentType

/**
 * Tracks intro/credits skip decisions for a single playback, ported from the
 * Swift PlayerViewModel skip-segment handling. A segment is offered (Skip Intro
 * / Skip Credits button) or auto-skipped at most **once per playback** — once
 * the user (or auto-skip) jumps past it, re-entering the same time range must
 * not re-show the button. Compose-free; the player ViewModel polls
 * [activeSegment] against the current position and drives the seek.
 */
class SegmentSkipTracker(
    private val segments: List<MediaSegmentDto>,
) {
    private val skipped = mutableSetOf<String>()

    /**
     * The segment covering [positionSeconds] that has not yet been skipped, or
     * null. The end is exclusive so a seek to `endSeconds` clears the segment.
     */
    fun activeSegment(positionSeconds: Double): MediaSegmentDto? =
        segments.firstOrNull { segment ->
            segment.type != MediaSegmentType.UNKNOWN &&
                segment.id !in skipped &&
                positionSeconds >= segment.startSeconds &&
                positionSeconds < segment.endSeconds
        }

    /**
     * The segment to auto-skip at [positionSeconds] given the user's auto-skip
     * settings, or null. Intro segments gate on [autoSkipIntro], Outro/Credits
     * on [autoSkipCredits]; other types are never auto-skipped.
     */
    fun autoSkipTarget(
        positionSeconds: Double,
        autoSkipIntro: Boolean,
        autoSkipCredits: Boolean,
    ): MediaSegmentDto? {
        val segment = activeSegment(positionSeconds) ?: return null
        // Matches the Swift checkCurrentSegment gating: intro + recap follow the
        // "skip intro" setting, credits (outro) + preview follow "skip credits".
        val enabled =
            when (segment.type) {
                MediaSegmentType.INTRO, MediaSegmentType.RECAP -> autoSkipIntro
                MediaSegmentType.OUTRO, MediaSegmentType.PREVIEW -> autoSkipCredits
                else -> false
            }
        return if (enabled) segment else null
    }

    /** Marks [id] skipped so it is never offered/auto-skipped again this playback. */
    fun markSkipped(id: String) {
        skipped.add(id)
    }

    /** True once every segment has been skipped (nothing left to offer). */
    val allSkipped: Boolean
        get() = segments.all { it.id in skipped }
}
