package dev.bitstorm.sashimi.core.playback

/**
 * Why playback needs rescuing. The :app layer maps Media3's error codes onto
 * these; the stall watchdog produces [STALL] itself.
 */
enum class PlaybackFailure {
    /** Buffering that never ends, or rebuffering over and over: the link cannot carry the stream. */
    STALL,

    /** A network/HTTP failure while fetching the stream. */
    NETWORK,

    /** The decoder refused or failed on the stream: a codec, profile, level or bit-depth the device cannot handle. */
    DECODE,

    /** The container or manifest could not be parsed. */
    SOURCE,

    OTHER,
}

/** What the recovery ladder decided to do next. */
sealed interface RecoveryDecision {
    /** Rebuild the same stream at the same position. */
    data object Retry : RecoveryDecision

    /** Re-negotiate with direct play and direct stream disabled, at the same cap. */
    data object ForceTranscode : RecoveryDecision

    /** Re-negotiate at a lower tier (always a transcode). */
    data class StepDown(
        val to: QualityOption,
    ) : RecoveryDecision

    /** Nothing left to try: surface a human-readable error with Retry. */
    data object GiveUp : RecoveryDecision
}

/**
 * The automatic recovery ladder, as a pure function so the policy is unit
 * tested without a player: first retry, then fall back from direct play to a
 * transcode, then step down tier by tier to the 720 kbps floor, then give up.
 *
 * Two kinds of trouble arrive through the same door and want different first
 * moves:
 *  - A link that cannot carry the stream ([PlaybackFailure.STALL] /
 *    [PlaybackFailure.NETWORK]). One retry covers a transient blip; after
 *    that only a lower bitrate helps, and a same-rate transcode of a direct
 *    play would just stall again, so the ladder goes straight to a lower tier
 *    (which is a transcode).
 *  - A stream the device cannot decode ([PlaybackFailure.DECODE] /
 *    [PlaybackFailure.SOURCE]). Retrying the same bytes is pointless; a
 *    transcode to h264/aac fixes it. If even the transcode fails, one retry
 *    and one step down (a lower resolution dodges a decoder's size limit),
 *    then stop: this is not a bandwidth problem.
 *
 * [sameQualityRetries] counts retries spent since the last healthy stretch of
 * playback or the last ladder step; it is NOT reset by a step down, so a link
 * that keeps stalling walks down the tiers without a wasted retry at each rung.
 * [attempts] counts every recovery since the last healthy stretch and bounds
 * the whole thing.
 */
object PlaybackRecoveryPlan {
    /** Hard ceiling on consecutive recoveries, whatever the ladder says. */
    const val MAX_ATTEMPTS = 8

    /** Non-bandwidth failures get this many recoveries before the error shows. */
    const val MAX_NON_LINK_ATTEMPTS = 3

    /**
     * Playback that has run this long since the last recovery is healthy: the
     * retry budget resets, so a later isolated blip gets a retry again rather
     * than a step down.
     */
    const val HEALTHY_PLAYBACK_MS = 60_000L

    fun decide(
        failure: PlaybackFailure,
        isTranscoding: Boolean,
        /** What the current stream runs at: the transcode's target, or the source bitrate for direct play. */
        currentBitrate: Int?,
        sameQualityRetries: Int,
        attempts: Int,
    ): RecoveryDecision {
        if (attempts >= MAX_ATTEMPTS) return RecoveryDecision.GiveUp
        val lower = currentBitrate?.let(QualityOption::steppedDown)
        return when (failure) {
            PlaybackFailure.STALL, PlaybackFailure.NETWORK ->
                when {
                    sameQualityRetries == 0 -> RecoveryDecision.Retry
                    lower != null -> RecoveryDecision.StepDown(lower)
                    !isTranscoding -> RecoveryDecision.ForceTranscode
                    else -> RecoveryDecision.GiveUp
                }
            PlaybackFailure.DECODE, PlaybackFailure.SOURCE ->
                when {
                    !isTranscoding -> RecoveryDecision.ForceTranscode
                    sameQualityRetries == 0 -> RecoveryDecision.Retry
                    lower != null && attempts < MAX_NON_LINK_ATTEMPTS -> RecoveryDecision.StepDown(lower)
                    else -> RecoveryDecision.GiveUp
                }
            PlaybackFailure.OTHER ->
                when {
                    sameQualityRetries == 0 -> RecoveryDecision.Retry
                    !isTranscoding -> RecoveryDecision.ForceTranscode
                    lower != null && attempts < MAX_NON_LINK_ATTEMPTS -> RecoveryDecision.StepDown(lower)
                    else -> RecoveryDecision.GiveUp
                }
        }
    }

    /** The short notice shown while a recovery step is applied. */
    fun notice(decision: RecoveryDecision): String? =
        when (decision) {
            RecoveryDecision.Retry -> "Reconnecting…"
            RecoveryDecision.ForceTranscode -> "Converting for this device…"
            is RecoveryDecision.StepDown -> "Lowering quality for your connection · ${decision.to.menuLabel}"
            RecoveryDecision.GiveUp -> null
        }

    /** What the user reads once the ladder is exhausted. */
    fun exhaustedMessage(failure: PlaybackFailure): String =
        when (failure) {
            PlaybackFailure.STALL, PlaybackFailure.NETWORK ->
                "Your connection can't keep up with this video right now. Check the network and try again."
            PlaybackFailure.DECODE, PlaybackFailure.SOURCE ->
                "This video can't be played on this device, even after converting it."
            PlaybackFailure.OTHER -> "Playback stopped unexpectedly."
        }
}

/**
 * Watches buffering and decides when it has become a stall, with an injected
 * clock so the thresholds are unit tested. The player's tick loop feeds it the
 * current state; it never touches the player.
 *
 * Two shapes of trouble count:
 *  - one long buffer: a transcode start or a seek is allowed longer than a
 *    mid-stream rebuffer;
 *  - a run of short rebuffers: a link that is nearly fast enough plays a few
 *    seconds, stalls, plays, stalls. Each one is under the long threshold, but
 *    several within a minute is a stall all the same.
 *
 * Buffering while paused does not count; a seek resets the current buffer's
 * clock (it is expected to buffer) but still counts towards the run.
 */
class StallDetector(
    private val clock: () -> Long,
    private val startupStallMs: Long = STARTUP_STALL_MS,
    private val rebufferStallMs: Long = REBUFFER_STALL_MS,
    private val rebufferRunCount: Int = REBUFFER_RUN_COUNT,
    private val rebufferRunWindowMs: Long = REBUFFER_RUN_WINDOW_MS,
) {
    private var bufferingSince: Long? = null
    private var hasPlayed = false
    private var seekPending = false
    private var bufferAfterSeek = false
    private val rebufferStarts = ArrayDeque<Long>()

    /** A new stream was prepared: nothing counts from before. */
    fun reset() {
        bufferingSince = null
        hasPlayed = false
        seekPending = false
        bufferAfterSeek = false
        rebufferStarts.clear()
    }

    /** The user seeked; the buffering that follows is expected. */
    fun noteSeek() {
        seekPending = true
        bufferingSince = null
    }

    /**
     * Feed the player's state. Returns true the moment the current buffering
     * qualifies as a stall; the caller then recovers and calls [reset].
     */
    fun update(
        isBuffering: Boolean,
        wantsToPlay: Boolean,
        isPlaying: Boolean,
    ): Boolean {
        val now = clock()
        if (isPlaying) {
            hasPlayed = true
            seekPending = false
            bufferingSince = null
            return false
        }
        if (!isBuffering || !wantsToPlay) {
            bufferingSince = null
            return false
        }
        val since =
            bufferingSince ?: run {
                bufferingSince = now
                bufferAfterSeek = seekPending
                if (hasPlayed && !seekPending) {
                    rebufferStarts.addLast(now)
                    while (rebufferStarts.isNotEmpty() && now - rebufferStarts.first() > rebufferRunWindowMs) rebufferStarts.removeFirst()
                }
                seekPending = false
                now
            }
        if (hasPlayed && rebufferStarts.size >= rebufferRunCount) return true
        // A seek on a transcode can restart the server's encode at the new
        // point, which takes as long as a start does.
        val limit = if (hasPlayed && !bufferAfterSeek) rebufferStallMs else startupStallMs
        return now - since >= limit
    }

    companion object {
        /** A transcode can take a while to start; a direct play on a slow link, longer. */
        const val STARTUP_STALL_MS = 30_000L

        /** A mid-stream buffer this long means the link is not keeping up. */
        const val REBUFFER_STALL_MS = 12_000L

        /** This many rebuffers inside the window is a stall even if each is short. */
        const val REBUFFER_RUN_COUNT = 3
        const val REBUFFER_RUN_WINDOW_MS = 60_000L
    }
}
