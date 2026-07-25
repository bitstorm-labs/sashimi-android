package dev.bitstorm.sashimi.core.playback

/**
 * Converts between the player's stream timeline and absolute positions in the
 * item.
 *
 * The two are not the same thing for a resumed transcode. Jellyfin bakes
 * `StartTimeTicks` into the `TranscodingUrl`, so the HLS stream it returns
 * physically begins at the resume point and its timeline is 0-based *there*.
 * Direct play and direct stream serve the whole file, so their timeline already
 * spans the item and the offset is zero.
 *
 * This lived inline and only half-existed: the initial seek was handled
 * correctly, but nothing added the offset back, so every consumer that meant
 * "how far into the item are we" was wrong by the resume offset. Resuming a
 * transcode at 1:30:00 and watching five minutes reported five minutes to the
 * server, discarding 85 minutes of progress for every client.
 */
object ResumeTimeline {
    private const val TICKS_PER_MS = 10_000L

    /**
     * Where the player should start on the stream's own timeline.
     *
     * Zero for a resumed transcode, because the stream already starts there;
     * the resume point for direct play and direct stream, which must seek.
     */
    fun playerStartMs(
        isTranscoding: Boolean,
        resumeTicks: Long,
    ): Long = if (isTranscoding && resumeTicks > 0) 0L else (resumeTicks / TICKS_PER_MS).coerceAtLeast(0)

    /** How far into the item the stream's timeline zero actually sits. */
    fun timelineOffsetMs(
        isTranscoding: Boolean,
        resumeTicks: Long,
    ): Long = if (isTranscoding && resumeTicks > 0) resumeTicks / TICKS_PER_MS else 0L

    /** Player timeline position -> absolute position in the item. */
    fun absoluteMs(
        offsetMs: Long,
        playerPositionMs: Long,
    ): Long = offsetMs + playerPositionMs.coerceAtLeast(0)

    /** Absolute position in the item -> player timeline position. */
    fun timelineMs(
        offsetMs: Long,
        absoluteMs: Long,
    ): Long = (absoluteMs - offsetMs).coerceAtLeast(0)

    /**
     * True when an absolute target sits before the start of the current stream,
     * so it cannot be reached by seeking and needs a re-negotiation.
     */
    fun requiresRenegotiation(
        offsetMs: Long,
        absoluteTargetMs: Long,
    ): Boolean = offsetMs > 0 && absoluteTargetMs < offsetMs
}
