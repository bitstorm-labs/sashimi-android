package dev.bitstorm.sashimi.core.model

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.ImageCacheEpoch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemImagesTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Trimmed from a real /Shows/{id}/Episodes response (The 100 S1E1).
    private val episode =
        json.decodeFromString(
            BaseItemDto.serializer(),
            """
            {"Id":"ep1","Name":"Pilot","Type":"Episode","SeriesId":"show1",
             "ImageTags":{"Primary":"epPrimaryTag"},"BackdropImageTags":[],
             "ParentBackdropItemId":"show1","ParentBackdropImageTags":["showBackdropTag"],
             "SeriesPrimaryImageTag":"showPrimaryTag",
             "ParentLogoItemId":"show1","ParentLogoImageTag":"showLogoTag"}
            """.trimIndent(),
        )

    private val movie =
        BaseItemDto(
            id = "m1",
            type = ItemType.MOVIE,
            imageTags = mapOf("Primary" to "mPrimary", "Logo" to "mLogo"),
            backdropImageTags = listOf("mBackdrop0", "mBackdrop1"),
        )

    private val client = JellyfinClient(deviceId = "test").also { it.configure("http://jf.local:8096", "tok", "user") }

    @Test
    fun `season poster falls back to the series poster`() {
        val season = BaseItemDto(id = "s2", type = ItemType.SEASON, seriesId = "show1", seriesPrimaryImageTag = "showTag")
        assertEquals(ImageRef("show1", "Primary", "showTag"), ItemImages.seasonPoster(season))
        val own = season.copy(imageTags = mapOf("Primary" to "seasonTag"))
        assertEquals(ImageRef("s2", "Primary", "seasonTag"), ItemImages.seasonPoster(own))
    }

    @Test
    fun `episode tags decode from the server JSON`() {
        assertEquals("showPrimaryTag", episode.seriesPrimaryImageTag)
        assertEquals("showLogoTag", episode.parentLogoImageTag)
    }

    @Test
    fun `episode detail picture is its own still with its Primary tag`() {
        assertEquals(ImageRef("ep1", "Primary", "epPrimaryTag"), ItemImages.detailBackdrop(episode, isYouTubeSeries = false))
    }

    @Test
    fun `episode card poster is the series poster with the series tag`() {
        assertEquals(ImageRef("show1", "Primary", "showPrimaryTag"), ItemImages.cardPoster(episode))
    }

    @Test
    fun `episode continue-watching art is the series backdrop with the parent tag`() {
        assertEquals(ImageRef("show1", "Backdrop", "showBackdropTag"), ItemImages.continueWatching(episode))
    }

    @Test
    fun `episode without a series backdrop falls back to its own still`() {
        val yt = episode.copy(parentBackdropImageTags = null)
        assertEquals(ImageRef("ep1", "Primary", "epPrimaryTag"), ItemImages.continueWatching(yt))
    }

    @Test
    fun `series logo for an episode carries the parent logo tag`() {
        assertEquals(ImageRef("show1", "Logo", "showLogoTag"), ItemImages.seriesLogo(episode))
    }

    @Test
    fun `movie backdrop uses the first backdrop tag and its own logo tag`() {
        assertEquals(ImageRef("m1", "Backdrop", "mBackdrop0"), ItemImages.detailBackdrop(movie, isYouTubeSeries = false))
        assertEquals(ImageRef("m1", "Backdrop", "mBackdrop0"), ItemImages.continueWatching(movie))
        assertEquals(ImageRef("m1", "Logo", "mLogo"), ItemImages.logo(movie))
        assertEquals(ImageRef("m1", "Primary", "mPrimary"), ItemImages.cardPoster(movie))
    }

    @Test
    fun `season without its own backdrop uses the series backdrop tag`() {
        val season = episode.copy(id = "s1", type = ItemType.SEASON)
        assertEquals(ImageRef("show1", "Backdrop", "showBackdropTag"), ItemImages.detailBackdrop(season, isYouTubeSeries = false))
    }

    @Test
    fun `person image carries the person tag`() {
        assertEquals(ImageRef("p1", "Primary", "pTag"), ItemImages.person(PersonInfo(id = "p1", name = "A", primaryImageTag = "pTag")))
    }

    @Test
    fun `image URL includes the tag`() {
        val url = client.imageURL(ItemImages.detailBackdrop(episode, false), 1280)
        assertEquals("http://jf.local:8096/Items/ep1/Images/Primary?maxWidth=1280&tag=epPrimaryTag", url)
        assertEquals(
            "http://jf.local:8096/Items/p1/Images/Primary?maxWidth=150&tag=pTag",
            client.personImageURL("p1", 150, "pTag"),
        )
    }

    @Test
    fun `a changed tag changes the URL, so the cache cannot serve the old picture`() {
        val before = client.imageURL(ItemImages.primary(episode), 400)
        val after = client.imageURL(ItemImages.primary(episode.copy(imageTags = mapOf("Primary" to "newTag"))), 400)
        assertTrue(before != after)
    }

    @Test
    fun `unknown tag builds the plain URL`() {
        assertEquals(
            "http://jf.local:8096/Items/x/Images/Primary?maxWidth=400",
            client.imageURL(ItemImages.primary(BaseItemDto(id = "x")), 400),
        )
    }

    @Test
    fun `image cache is cleared once per epoch`() {
        assertTrue(ImageCacheEpoch.needsClear(0))
        assertFalse(ImageCacheEpoch.needsClear(ImageCacheEpoch.CURRENT))
    }
}
