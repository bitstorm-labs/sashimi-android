package dev.bitstorm.sashimi.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ImageRef
import dev.bitstorm.sashimi.core.model.ImageSizing
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

    /*
     * Every width below is the REQUESTED pixel width: compute it from the on-screen
     * size with [rememberImageWidth] (or ImageSizing in non-UI code), never a
     * hard-coded number, or a dense screen gets an upscaled, soft picture.
     */

    fun primary(
        item: BaseItemDto,
        widthPx: Int,
    ): String? = url(ItemImages.primary(item), widthPx)

    fun seasonPoster(
        season: BaseItemDto,
        widthPx: Int,
    ): String? = url(ItemImages.seasonPoster(season), widthPx)

    fun banner(
        item: BaseItemDto,
        widthPx: Int,
    ): String? = url(ItemImages.banner(item), widthPx)

    fun logo(
        item: BaseItemDto,
        widthPx: Int,
    ): String? = url(ItemImages.logo(item), widthPx)

    fun seriesLogo(
        item: BaseItemDto,
        widthPx: Int,
    ): String? = ItemImages.seriesLogo(item)?.let { url(it, widthPx) }

    fun person(
        person: PersonInfo,
        widthPx: Int,
    ): String? = client.personImageURL(person.id, widthPx, person.primaryImageTag)

    /** A person by id when only the route is at hand; [tag] when the caller has it. */
    fun person(
        personId: String,
        tag: String?,
        widthPx: Int,
    ): String? = client.personImageURL(personId, widthPx, tag)

    /** Poster for a card: episodes use the series poster. */
    fun cardPoster(
        item: BaseItemDto,
        widthPx: Int,
    ): String? = url(ItemImages.cardPoster(item), widthPx)

    /** 16:9 art for the Continue Watching card. */
    fun continueWatching(
        item: BaseItemDto,
        widthPx: Int,
    ): String? = url(ItemImages.continueWatching(item), widthPx)

    /** Detail picture (see [ItemImages.detailBackdrop]). */
    fun detailBackdrop(
        item: BaseItemDto,
        isYouTubeSeries: Boolean,
        widthPx: Int,
    ): String? = url(ItemImages.detailBackdrop(item, isYouTubeSeries), widthPx)
}

/**
 * The pixel width to request for an image shown [width] wide on this screen. For
 * a ContentScale.Crop box, pass its [height] and the picture's [imageAspect]
 * (width / height) so a wide picture in a tall box is not upscaled.
 */
@Composable
fun rememberImageWidth(
    width: Dp,
    height: Dp? = null,
    imageAspect: Float? = null,
): Int {
    val density = LocalDensity.current.density
    return remember(width, height, imageAspect, density) {
        ImageSizing.requestWidth(width.value, density, height?.value, imageAspect)
    }
}

/** Image URLs for the active server. */
object ImageUrls : ImageUrlBuilder({ ServiceLocator.client })

/**
 * The image builder for the server the current screen belongs to. Defaults to
 * the active server; a cross-server detail provides its own.
 */
val LocalImageUrls = staticCompositionLocalOf<ImageUrlBuilder> { ImageUrls }
