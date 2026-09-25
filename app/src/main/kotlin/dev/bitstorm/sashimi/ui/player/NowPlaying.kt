package dev.bitstorm.sashimi.ui.player

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType

/**
 * What the system media surfaces (the MediaSession, and through it the PiP
 * window, Bluetooth/car displays and the output switcher) show for an item.
 *
 * Kept free of Media3 and android.net.Uri so it is testable on the plain JVM;
 * [PlayerViewModel] converts it to a MediaMetadata.
 *
 * Episodes lead with the episode name, and carry the series and SxEy in the
 * subtitle, which is how system media UIs read (title over artist). The
 * in-player chrome deliberately does it the other way round (series as the
 * title); that is a different surface with more room.
 */
data class NowPlaying(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
) {
    companion object {
        fun from(
            item: BaseItemDto,
            artworkUrl: (BaseItemDto) -> String?,
        ): NowPlaying {
            val subtitle =
                when (item.type) {
                    ItemType.EPISODE -> {
                        val s = item.parentIndexNumber
                        val e = item.indexNumber
                        val code = if (s != null && e != null) "S$s:E$e" else null
                        listOfNotNull(item.seriesName?.takeIf { it.isNotBlank() }, code)
                            .joinToString(" · ")
                            .ifEmpty { null }
                    }
                    else -> item.productionYear?.toString()
                }
            val title =
                item.name.takeIf { it.isNotBlank() }
                    ?: item.seriesName?.takeIf { it.isNotBlank() }
                    ?: ""
            return NowPlaying(title = title, subtitle = subtitle, artworkUrl = artworkUrl(item))
        }
    }
}
