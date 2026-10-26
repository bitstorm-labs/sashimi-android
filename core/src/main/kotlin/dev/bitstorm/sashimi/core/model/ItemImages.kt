package dev.bitstorm.sashimi.core.model

/**
 * One Jellyfin image: whose, which kind, and the server's tag for it. The tag
 * changes whenever the server replaces the picture, and image URLs carry it, so
 * a URL-keyed cache (Coil) fetches the new one instead of showing the old one
 * forever. A null tag (unknown) just builds the untagged URL.
 */
data class ImageRef(
    val itemId: String,
    val type: String,
    val tag: String?,
)

/**
 * Which image each surface shows, with its tag. Pure and Compose-free so the
 * choice is unit-tested and reusable by a TV module.
 */
object ItemImages {
    const val PRIMARY = "Primary"
    const val BACKDROP = "Backdrop"
    const val LOGO = "Logo"
    const val BANNER = "Banner"

    fun primary(item: BaseItemDto): ImageRef = ImageRef(item.id, PRIMARY, item.imageTags?.get(PRIMARY))

    /** A season page's poster: the season's own Primary, else its series' poster. */
    fun seasonPoster(season: BaseItemDto): ImageRef {
        val seriesId = season.seriesId
        return if (season.imageTags?.get(PRIMARY) == null && seriesId != null) {
            ImageRef(seriesId, PRIMARY, season.seriesPrimaryImageTag)
        } else {
            primary(season)
        }
    }

    fun banner(item: BaseItemDto): ImageRef = ImageRef(item.id, BANNER, item.imageTags?.get(BANNER))

    fun logo(item: BaseItemDto): ImageRef = ImageRef(item.id, LOGO, item.imageTags?.get(LOGO))

    /** The parent series' logo, for an episode/season title block. */
    fun seriesLogo(item: BaseItemDto): ImageRef? = item.seriesId?.let { ImageRef(it, LOGO, item.parentLogoImageTag) }

    fun person(person: PersonInfo): ImageRef = ImageRef(person.id, PRIMARY, person.primaryImageTag)

    /** The item's own first backdrop. */
    fun backdrop(item: BaseItemDto): ImageRef = ImageRef(item.id, BACKDROP, item.backdropImageTags?.firstOrNull())

    /** The parent series' first backdrop (episodes/seasons), or null without a series. */
    fun seriesBackdrop(item: BaseItemDto): ImageRef? =
        item.seriesId?.let { ImageRef(it, BACKDROP, item.parentBackdropImageTags?.firstOrNull()) }

    /** Card poster: episodes show the series poster, everything else its own Primary. */
    fun cardPoster(item: BaseItemDto): ImageRef {
        val seriesId = item.seriesId
        return if (item.type == ItemType.EPISODE && seriesId != null) {
            ImageRef(seriesId, PRIMARY, item.seriesPrimaryImageTag)
        } else {
            primary(item)
        }
    }

    /**
     * 16:9 Continue Watching art. Regular episodes use the series backdrop;
     * episodes without one (YouTube) use their own still; videos their Primary;
     * movies/series their own backdrop.
     */
    fun continueWatching(item: BaseItemDto): ImageRef =
        when (item.type) {
            ItemType.EPISODE ->
                if (item.parentBackdropImageTags?.isNotEmpty() == true) {
                    ImageRef(item.seriesId ?: item.id, BACKDROP, item.parentBackdropImageTags.first())
                } else {
                    primary(item)
                }
            ItemType.VIDEO -> primary(item)
            else -> backdrop(item)
        }

    /**
     * Detail picture. Episodes use their own still; YouTube series the banner;
     * otherwise the item's backdrop, then the parent series' backdrop, then the
     * item's Primary.
     */
    fun detailBackdrop(
        item: BaseItemDto,
        isYouTubeSeries: Boolean,
    ): ImageRef {
        if (item.type == ItemType.EPISODE) return primary(item)
        if (isYouTubeSeries) return banner(item)
        if (item.backdropImageTags?.isNotEmpty() == true) return backdrop(item)
        if (item.parentBackdropImageTags?.isNotEmpty() == true) seriesBackdrop(item)?.let { return it }
        return primary(item)
    }
}
