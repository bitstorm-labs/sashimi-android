package dev.bitstorm.sashimi.core.playback

/** Subtitle codec classification shared by streaming and downloads. */
object SubtitleCodecs {
    /** Image-based subtitle codecs: not text, so the server cannot deliver them as VTT. */
    val IMAGE_CODECS =
        setOf("pgssub", "hdmv_pgs_subtitle", "pgs", "dvbsub", "dvb_subtitle", "dvdsub", "dvd_subtitle", "vobsub", "xsub")

    /** True for a text codec; an unknown or missing codec is assumed text (the server will VTT it). */
    fun isText(codec: String?): Boolean = codec?.lowercase()?.trim() !in IMAGE_CODECS
}

/**
 * How a subtitle track reaches the screen. Jellyfin's `IsExternal` means "a
 * separate file on disk", not "deliverable separately": an SRT muxed inside an
 * MKV is `IsExternal=false` but the server's VTT endpoint extracts it just the
 * same, which is how jellyfin-web shows embedded text subtitles on a transcode.
 * The old code side-loaded only external files, so picking an embedded text
 * track on a transcode checked the chip and showed nothing.
 */
enum class SubtitleDelivery {
    /** A subtitle file next to the video: cheap to fetch, side-loaded up front. */
    EXTERNAL_FILE,

    /**
     * A text track muxed in the container. Side-loaded as VTT too, but only
     * when selected: the server extracts it from the whole file on first
     * request, which can take a long time for a big remote file, and the player
     * cannot start until every side-loaded track has answered.
     */
    EMBEDDED_TEXT,

    /**
     * An image track (PGS, VobSub, DVB). Rendered by asking the server to burn
     * it in, which is a transcode; it is never auto-selected, and the server is
     * never left to pick one on its own.
     */
    BURN_IN,

    ;

    companion object {
        fun of(
            codec: String?,
            isExternal: Boolean,
        ): SubtitleDelivery =
            when {
                !SubtitleCodecs.isText(codec) -> BURN_IN
                isExternal -> EXTERNAL_FILE
                else -> EMBEDDED_TEXT
            }
    }
}

/** What a subtitle change needs from the player. */
enum class SubtitleChange {
    /** Select among tracks the player already has. */
    IN_PLAYER,

    /** Rebuild the media item at the same position with the new track side-loaded. */
    RELOAD,

    /** Re-negotiate with the server: a burn-in starts or stops. */
    RENEGOTIATE,
}

/**
 * Pure subtitle decisions: which index the server is told about, which tracks
 * are side-loaded, and what a selection change costs.
 */
object SubtitleDecisions {
    /**
     * Sent as `SubtitleStreamIndex` when no burn-in is wanted. Leaving the
     * field null lets the server apply the user's Jellyfin subtitle preference,
     * which for an image track means a burned-in subtitle the app shows as
     * "Off" and cannot switch off. -1 is "no subtitle", explicitly.
     */
    const val NO_SERVER_SUBTITLE = -1

    /**
     * The `SubtitleStreamIndex` to negotiate with: the selected image track
     * when the stream will be a transcode (the only way to show one there),
     * else [NO_SERVER_SUBTITLE]. Sending an image index without forcing a
     * transcode would itself turn a direct play into a burn-in transcode.
     */
    fun serverIndex(
        selected: SubtitleTrack?,
        transcoding: Boolean,
    ): Int =
        if (transcoding && selected != null && !selected.isOff && selected.delivery == SubtitleDelivery.BURN_IN) {
            selected.index
        } else {
            NO_SERVER_SUBTITLE
        }

    /**
     * The tracks to side-load as VTT: every external file, plus the selected
     * embedded text track when the stream does not carry it. A direct play
     * hands the player the original container, whose embedded tracks the
     * player reads itself; a transcode's HLS stream carries none. Burn-ins are
     * the server's job.
     */
    fun sideLoaded(
        tracks: List<SubtitleTrack>,
        selectedIndex: Int,
        streamCarriesEmbedded: Boolean,
    ): List<SubtitleTrack> =
        tracks.filter { track ->
            !track.isOff &&
                (
                    track.delivery == SubtitleDelivery.EXTERNAL_FILE ||
                        (track.delivery == SubtitleDelivery.EMBEDDED_TEXT && !streamCarriesEmbedded && track.index == selectedIndex)
                )
        }

    /** What switching from [from] to [to] (either may be Off/null) requires. */
    fun change(
        from: SubtitleTrack?,
        to: SubtitleTrack?,
        streamCarriesEmbedded: Boolean,
    ): SubtitleChange {
        // A direct play carries the image tracks too, and Media3 renders PGS,
        // VobSub and DVB from the container itself: no transcode needed.
        if (streamCarriesEmbedded) return SubtitleChange.IN_PLAYER
        val fromBurn = from != null && !from.isOff && from.delivery == SubtitleDelivery.BURN_IN
        val toBurn = to != null && !to.isOff && to.delivery == SubtitleDelivery.BURN_IN
        return when {
            fromBurn || toBurn -> SubtitleChange.RENEGOTIATE
            to != null && !to.isOff && to.delivery == SubtitleDelivery.EMBEDDED_TEXT -> SubtitleChange.RELOAD
            else -> SubtitleChange.IN_PLAYER
        }
    }

    /**
     * The track to pre-select when playback starts, or null for none: the
     * user's preferred language among the TEXT tracks, else the first text
     * track. An image track is never auto-selected, because showing it means
     * a full transcode the user did not ask for; they can still pick it.
     */
    fun initialSelection(
        tracks: List<SubtitleTrack>,
        subtitlesEnabled: Boolean,
        preferredLanguage: String,
        matches: (trackLanguage: String?, preferred: String) -> Boolean,
    ): SubtitleTrack? {
        if (!subtitlesEnabled) return null
        val text = tracks.filter { !it.isOff && it.delivery != SubtitleDelivery.BURN_IN }
        if (text.isEmpty()) return null
        if (preferredLanguage.isNotEmpty()) {
            text.firstOrNull { matches(it.languageCode, preferredLanguage) }?.let { return it }
        }
        return text.first()
    }
}
