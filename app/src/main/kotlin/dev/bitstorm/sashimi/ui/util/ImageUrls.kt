package dev.bitstorm.sashimi.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ImageRef
import dev.bitstorm.sashimi.core.model.ItemImages
import dev.bitstorm.sashimi.core.model.PersonInfo
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.di.ServiceLocator

/**
 * Builds Jellyfin image URLs for Coil. Ported from the scattered `imageURL`
 * computed properties across the Swift views. Jellyfin image endpoints don't
 * require the auth header, so these plain URLs load directly in Coil (the Swift
 * app builds them the same way, straight off `serverURL`).
 *
 * Bound to one client: [ImageUrls] follows the shared (active-server) client;
 * a screen showing another server's item builds its own over that server's
 * client, since an item id means nothing on a different server.
 */
open class ImageUrlBuilder(
    private val clientProvider: () -> JellyfinClient,
) {
    private val client get() = clientProvider()

    /**
     * Every URL carries the image's tag where the item knows it (see [ImageRef]),
     * so a picture replaced on the server is fetched fresh, not served from cache.
     */
    fun url(
        ref: ImageRef,
        maxWidth: Int,
    ): String? = client.imageURL(ref, maxWidth)

    fun primary(
        item: BaseItemDto,
        maxWidth: Int,
    ): String? = url(ItemImages.primary(item), maxWidth)

    fun banner(item: BaseItemDto): String? = url(ItemImages.banner(item), 1920)

    fun logo(item: BaseItemDto): String? = url(ItemImages.logo(item), 500)

    fun seriesLogo(item: BaseItemDto): String? = ItemImages.seriesLogo(item)?.let { url(it, 500) }

    fun person(person: PersonInfo): String? = client.personImageURL(person.id, 150, person.primaryImageTag)

    /** A person by id when only the route is at hand; [tag] when the caller has it. */
    fun person(
        personId: String,
        tag: String?,
    ): String? = client.personImageURL(personId, 150, tag)

    /** Poster for a card: episodes use the series poster. */
    fun cardPoster(
        item: BaseItemDto,
        width: Int,
    ): String? = url(ItemImages.cardPoster(item), width * 2)

    /** 16:9 art for the Continue Watching card. */
    fun continueWatching(
        item: BaseItemDto,
        width: Int,
    ): String? = url(ItemImages.continueWatching(item), width * 3)

    /** Detail picture (see [ItemImages.detailBackdrop]). */
    fun detailBackdrop(
        item: BaseItemDto,
        isYouTubeSeries: Boolean,
    ): String? = url(ItemImages.detailBackdrop(item, isYouTubeSeries), 1280)
}

/** Image URLs for the active server. */
object ImageUrls : ImageUrlBuilder({ ServiceLocator.client })

/**
 * The image builder for the server the current screen belongs to. Defaults to
 * the active server; a cross-server detail provides its own.
 */
val LocalImageUrls = staticCompositionLocalOf<ImageUrlBuilder> { ImageUrls }
