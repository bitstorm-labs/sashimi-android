package dev.bitstorm.sashimi.ui.player

import androidx.media3.common.Player

/**
 * Hands the player from one stream to the next: every re-negotiation (a
 * quality or audio change, a backward scrub past a resumed transcode, a stall
 * recovery step, the next episode) goes through [retire] before it negotiates.
 *
 * The player used to keep the outgoing stream loaded, and playing, through the
 * whole negotiation. Its transcode had already been stopped, so its next
 * segment request made the server start ffmpeg again for a session nobody
 * owned, and that stream's audio played on under the spinner or an error.
 * Anything that later called play() or prepare() on it (a media button, the
 * PiP play action, audio focus coming back) could bring it back too. The same
 * bug on Apple TV was sashimi-apple #886.
 */
internal class StreamHandoff(
    private val player: Player,
    /** Ends a server transcode by its play session id. */
    private val stopTranscode: suspend (playSessionId: String) -> Unit,
) {
    /**
     * Where in the item the viewer is while no stream is loaded: from [retire]
     * until [handOver]. Null while a stream is up.
     */
    var heldPositionMs: Long? = null
        private set

    /**
     * Lets go of the current stream. [priorTranscodeSession] is the outgoing
     * stream's play session id when the server is transcoding it, and
     * [positionMs] where in the item the next stream will start.
     */
    suspend fun retire(
        priorTranscodeSession: String?,
        positionMs: Long,
    ) {
        // Held first: stopping the player reports a pause, at this position.
        heldPositionMs = positionMs
        // Unloaded, not paused: a paused stream is still there to resume, and
        // keeps fetching. Before the server is told to stop it, so no request
        // for it can arrive after.
        player.stop()
        player.clearMediaItems()
        priorTranscodeSession?.let { stopTranscode(it) }
    }

    /** The next stream is about to be loaded: positions come from the player again. */
    fun handOver() {
        heldPositionMs = null
    }
}
