package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import java.util.Locale

/** Card and Details text for a playing session. */
object SessionTitles {
    private const val TICKS_PER_SECOND = 10_000_000L

    /** The series for an episode, else the item's own name. */
    fun title(item: BaseItemDto?): String {
        if (item == null) return "Nothing playing"
        return if (item.type == ItemType.EPISODE && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
    }

    /** "S6:E10 · Matryoshka" for an episode; null otherwise. */
    fun episodeLine(item: BaseItemDto?): String? {
        if (item?.type != ItemType.EPISODE || item.seriesName.isNullOrBlank()) return null
        val season = item.parentIndexNumber
        val episode = item.indexNumber
        val code =
            when {
                season != null && episode != null -> "S$season:E$episode"
                episode != null -> "E$episode"
                else -> null
            }
        return listOfNotNull(code, item.name.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
    }

    /** Played fraction in 0..1; null without a runtime. */
    fun progress(
        positionTicks: Long?,
        runtimeTicks: Long?,
    ): Float? {
        if (positionTicks == null || runtimeTicks == null || runtimeTicks <= 0) return null
        return (positionTicks.toDouble() / runtimeTicks).coerceIn(0.0, 1.0).toFloat()
    }

    /** "12:04 / 42:24", or "1:02:03 / 2:10:00" past an hour; null without a position. */
    fun position(
        positionTicks: Long?,
        runtimeTicks: Long?,
    ): String? {
        positionTicks ?: return null
        val pos = clock(positionTicks)
        return if (runtimeTicks != null && runtimeTicks > 0) "$pos / ${clock(runtimeTicks)}" else pos
    }

    private fun clock(ticks: Long): String {
        val total = (ticks / TICKS_PER_SECOND).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** The Details sheet, as label/value pairs. Nothing here is secret: no ids, tokens or addresses. */
    fun details(session: SessionInfoDto): List<Pair<String, String>> =
        buildList {
            session.userName?.let { add("User" to it) }
            listOfNotNull(
                session.deviceName,
                session.client,
            ).distinct().joinToString(" · ").takeIf { it.isNotEmpty() }?.let { add("Device" to it) }
            add("Play mode" to PlayModes.of(session.transcodingInfo).label)
            PlayModes.detail(session).takeIf { it.isNotEmpty() }?.let { add("Detail" to it) }
            ServerSummary.bitrate(session)?.let { add("Bitrate" to ServerSummary.mbps(it)) }
            session.transcodingInfo?.let { t ->
                val stream =
                    listOfNotNull(
                        t.container?.uppercase(Locale.US),
                        t.videoCodec?.uppercase(Locale.US),
                        t.audioCodec?.uppercase(Locale.US),
                        if (t.width != null && t.height != null) "${t.width}×${t.height}" else null,
                    ).joinToString(" · ")
                if (stream.isNotEmpty()) add("Sending" to stream)
                // Only a video encode uses the GPU; a remux or audio conversion reports the type anyway.
                val encodesVideo = PlayModes.of(t) == PlayMode.CONVERTING_VIDEO
                if (encodesVideo && HardwareTranscoding.isOn(t.hardwareAccelerationType)) {
                    add("Hardware" to HardwareTranscoding.label(t.hardwareAccelerationType))
                }
            }
            position(session.playState?.positionTicks, session.nowPlayingItem?.runTimeTicks)?.let { add("Position" to it) }
            if (session.playState?.isPaused == true) add("State" to "Paused")
            add("Remote control" to if (session.supportsRemoteControl) "Yes" else "Not supported by this app")
        }
}
