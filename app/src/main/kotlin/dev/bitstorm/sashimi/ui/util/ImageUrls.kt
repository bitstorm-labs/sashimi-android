package dev.bitstorm.sashimi.ui.util

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.di.ServiceLocator

/**
 * Builds Jellyfin image URLs for Coil. Ported from the scattered `imageURL`
 * computed properties across the Swift views. Jellyfin image endpoints don't
 * require the auth header, so these plain URLs load directly in Coil (the Swift
 * app builds them the same way, straight off `serverURL`).
 */
object ImageUrls {
    private val client get() = ServiceLocator.client

    fun primary(
        itemId: String,
        maxWidth: Int,
    ): String? = client.imageURL(itemId, "Primary", maxWidth)

    fun backdrop(
        itemId: String,
        maxWidth: Int = 1280,
    ): String? = client.imageURL(itemId, "Backdrop", maxWidth)

    fun logo(itemId: String): String? = client.imageURL(itemId, "Logo", 500)

    fun banner(itemId: String): String? = client.imageURL(itemId, "Banner", 1920)

    fun person(personId: String): String? = client.personImageURL(personId, 150)

    /**
     * Poster for a card. Episodes use the series poster (regular shows) except
     * YouTube landscape thumbnails; everything else uses its own Primary. Port
     * of MobileRecentlyAddedCard.imageURL.
     */
    fun cardPoster(
        item: BaseItemDto,
        width: Int,
    ): String? {
        val id =
            if (item.type == ItemType.EPISODE && item.seriesId != null) item.seriesId!! else item.id
        return primary(id, width * 2)
    }

    /**
     * 16:9 art for the Continue Watching card. Regular episodes use the series
     * backdrop; YouTube episodes (no parent backdrop) use the episode thumbnail;
     * movies/series use their own backdrop. Port of MobileContinueWatchingCard.
     */
    fun continueWatching(
        item: BaseItemDto,
        width: Int,
    ): String? {
        val seriesHasBackdrop = item.parentBackdropImageTags?.isNotEmpty() == true
        val maxWidth = width * 3
        return when (item.type) {
            ItemType.EPISODE ->
                if (seriesHasBackdrop) {
                    client.imageURL(item.seriesId ?: item.id, "Backdrop", maxWidth)
                } else {
                    client.imageURL(item.id, "Primary", maxWidth)
                }
            ItemType.VIDEO -> client.imageURL(item.id, "Primary", maxWidth)
            else -> client.imageURL(item.id, "Backdrop", maxWidth)
        }
    }

    /**
     * Detail backdrop. Episodes → own Primary; YouTube series → Banner; else the
     * item (or parent) Backdrop, falling back to Primary. Port of the Swift
     * detail backdropImageURL.
     */
    fun detailBackdrop(
        item: BaseItemDto,
        isYouTubeSeries: Boolean,
    ): String? {
        if (item.type == ItemType.EPISODE) return primary(item.id, 1280)
        if (isYouTubeSeries) return banner(item.id)
        val id =
            when {
                item.backdropImageTags?.isNotEmpty() == true -> item.id
                item.parentBackdropImageTags?.isNotEmpty() == true && item.seriesId != null -> item.seriesId!!
                else -> return primary(item.id, 1280)
            }
        return backdrop(id, 1280)
    }
}
