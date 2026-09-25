package dev.bitstorm.sashimi.core.trickplay

import dev.bitstorm.sashimi.core.model.TrickplayInfo
import kotlin.math.abs

/** The trickplay resolution chosen for one item + media source. */
data class TrickplayTrack(
    val itemId: String,
    val mediaSourceId: String,
    val info: TrickplayInfo,
)

/** A thumbnail's pixel rectangle within its tile sheet. */
data class TileRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Where the thumbnail for a scrub position lives: which sheet, and which cell of it. */
data class TrickplayFrame(
    val sheetIndex: Int,
    val cell: TileRect,
)

/**
 * Pure geometry for Jellyfin trickplay (scrub thumbnails), kept in :core so it
 * is unit-tested. Jellyfin packs thumbnails row-major into
 * TileWidth x TileHeight JPEG sheets served at
 * `/Videos/{itemId}/Trickplay/{width}/{sheetIndex}.jpg`; thumbnail n covers
 * [n * Interval, (n + 1) * Interval) ms. Same maths as sashimi-roku's
 * PlayerScreen showTrickplay.
 */
object TrickplayMath {
    /** Roughly the width the other clients show a scrub thumbnail at. */
    const val TARGET_WIDTH = 320

    /**
     * Picks a usable resolution from an item's `Trickplay` map
     * (mediaSourceId -> width -> info). Prefers [preferredMediaSourceId]'s
     * entry, else the first source that has any. Within a source, the width
     * closest to [targetWidth] wins (ties go to the larger). Null when there is
     * no trickplay data or none of it is well-formed -- the caller shows nothing.
     */
    fun select(
        itemId: String,
        trickplay: Map<String, Map<String, TrickplayInfo>>?,
        preferredMediaSourceId: String?,
        targetWidth: Int = TARGET_WIDTH,
    ): TrickplayTrack? {
        if (trickplay.isNullOrEmpty()) return null
        val sources =
            buildList {
                preferredMediaSourceId?.let { id -> trickplay[id]?.let { add(id to it) } }
                trickplay.forEach { (id, byWidth) -> if (id != preferredMediaSourceId) add(id to byWidth) }
            }
        for ((sourceId, byWidth) in sources) {
            val best =
                byWidth.values
                    .filter(::isUsable)
                    .minWithOrNull(compareBy<TrickplayInfo> { abs(it.width - targetWidth) }.thenByDescending { it.width })
            if (best != null) return TrickplayTrack(itemId, sourceId, best)
        }
        return null
    }

    /** Thumbnail index for an absolute item position, clamped to the ones that exist. */
    fun thumbnailIndex(
        info: TrickplayInfo,
        positionMs: Long,
    ): Int {
        val raw = (positionMs.coerceAtLeast(0) / info.interval).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return raw.coerceAtMost(info.thumbnailCount - 1)
    }

    private fun perSheet(info: TrickplayInfo): Int = info.tileWidth * info.tileHeight

    /** Which sheet thumbnail [index] is on. */
    fun sheetIndex(
        info: TrickplayInfo,
        index: Int,
    ): Int = index / perSheet(info)

    /** The pixel rectangle of thumbnail [index] within its sheet (row-major). */
    fun cellRect(
        info: TrickplayInfo,
        index: Int,
    ): TileRect {
        val within = index % perSheet(info)
        val col = within % info.tileWidth
        val row = within / info.tileWidth
        return TileRect(col * info.width, row * info.height, info.width, info.height)
    }

    /** Sheet + cell for an absolute item position. */
    fun frameAt(
        info: TrickplayInfo,
        positionMs: Long,
    ): TrickplayFrame {
        val index = thumbnailIndex(info, positionMs)
        return TrickplayFrame(sheetIndex(info, index), cellRect(info, index))
    }

    private fun isUsable(info: TrickplayInfo): Boolean =
        info.width > 0 && info.height > 0 && info.tileWidth > 0 && info.tileHeight > 0 &&
            info.interval > 0 && info.thumbnailCount > 0
}
