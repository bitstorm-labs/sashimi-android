package dev.bitstorm.sashimi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {
    private val phone = 2.625f // 1080x2400 @ 420dpi
    private val bigPhone = 3.5f // 1440p @ 560dpi
    private val tablet = 2f // 1600x2560 @ 320dpi

    @Test
    fun `bucket rounds up to the ladder and caps at the ceiling`() {
        assertEquals(160, ImageSizing.bucket(1))
        assertEquals(320, ImageSizing.bucket(289))
        assertEquals(320, ImageSizing.bucket(320))
        assertEquals(480, ImageSizing.bucket(321))
        assertEquals(3840, ImageSizing.bucket(5000))
    }

    @Test
    fun `request is never smaller than the on-screen pixels`() {
        for (density in listOf(1f, 1.5f, tablet, phone, 3f, bigPhone, 4f)) {
            for (dp in listOf(52, 56, 70, 96, 110, 120, 160, 260, 300, 411, 560, 800, 1280)) {
                val px = dp * density
                val req = ImageSizing.requestWidth(dp.toFloat(), density)
                assertTrue("$dp dp @ $density: $req < $px", req >= px || req == ImageSizing.LADDER.last())
            }
        }
    }

    @Test
    fun `a fraction of a pixel over a step goes to the next step`() {
        assertEquals(320, ImageSizing.requestWidth(160f, 2f)) // exactly 320px
        assertEquals(480, ImageSizing.requestWidth(160.25f, 2f)) // 320.5px
    }

    @Test
    fun `request never overshoots by more than one ladder step`() {
        // A 110dp poster on a 3x phone is 330px: 480, not 4K.
        assertEquals(480, ImageSizing.requestWidth(110f, 3f))
        assertEquals(320, ImageSizing.requestWidth(110f, phone))
    }

    @Test
    fun `home poster, cast photo and episode row on a big phone`() {
        assertEquals(480, ImageSizing.requestWidth(110f, bigPhone)) // 385px
        assertEquals(320, ImageSizing.requestWidth(70f, bigPhone)) // 245px; was 150
        assertEquals(480, ImageSizing.requestWidth(120f, bigPhone, 68f, ImageSizing.LANDSCAPE_ASPECT)) // 420px; was 400
    }

    @Test
    fun `crop of a wide image into a tall box needs the height-driven width`() {
        // 100dp x 200dp box showing a 16:9 picture: 200 x 16/9 = 356dp wide.
        assertEquals(960, ImageSizing.requestWidth(100f, phone, 200f, ImageSizing.LANDSCAPE_ASPECT)) // 934px
        // A portrait poster in a portrait box is width-driven.
        assertEquals(320, ImageSizing.requestWidth(110f, phone, 165f, ImageSizing.POSTER_ASPECT))
    }

    @Test
    fun `detail picture on phones and the tablet hero`() {
        // Phone hero: full width x 220dp, 16:9 backdrop cropped.
        assertEquals(1280, ImageSizing.requestWidth(411f, phone, 220f, ImageSizing.LANDSCAPE_ASPECT)) // 1079px
        assertEquals(1600, ImageSizing.requestWidth(411f, bigPhone, 220f, ImageSizing.LANDSCAPE_ASPECT)) // 1439px; was 1280
        // Tablet wide hero: 55% of 1280dp at 2x = 1408px; was 1280.
        assertEquals(1600, ImageSizing.requestWidth(1280f * 0.55f, tablet))
    }
}
