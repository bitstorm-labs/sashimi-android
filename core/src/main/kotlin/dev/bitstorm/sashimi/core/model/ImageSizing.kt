package dev.bitstorm.sashimi.core.model

import kotlin.math.ceil
import kotlin.math.max

/**
 * How wide an image to ask the server for, from how big it is on screen.
 *
 * The request is the on-screen width in pixels (dp x density), rounded UP to a
 * fixed ladder of widths. Rounding up means the picture is never upscaled; the
 * ladder keeps URLs (and so cache entries) stable across nearby sizes and caps
 * the overshoot, so a small poster never asks for a 4K image.
 *
 * Pure Kotlin so a TV module reuses it and the maths is unit-tested.
 */
object ImageSizing {
    /** Widths the app ever requests. Ascending; the last is the ceiling. */
    val LADDER = intArrayOf(160, 240, 320, 480, 640, 800, 960, 1280, 1600, 1920, 2560, 3840)

    const val POSTER_ASPECT = 2f / 3f
    const val LANDSCAPE_ASPECT = 16f / 9f

    /**
     * Width to request for an image drawn [widthDp] wide at [density].
     *
     * For a ContentScale.Crop box pass [heightDp] and the image's own
     * [imageAspect] (width / height): a wide image cropped into a taller box is
     * scaled to the box HEIGHT, so it needs `height x aspect` pixels of width,
     * which can exceed the box width.
     */
    fun requestWidth(
        widthDp: Float,
        density: Float,
        heightDp: Float? = null,
        imageAspect: Float? = null,
    ): Int {
        val boxWidthPx = widthDp * density
        val cropWidthPx = if (heightDp != null && imageAspect != null) heightDp * density * imageAspect else 0f
        return bucket(ceil(max(boxWidthPx, cropWidthPx)).toInt())
    }

    /** The smallest ladder width >= [px], or the ceiling. */
    fun bucket(px: Int): Int = LADDER.firstOrNull { it >= px } ?: LADDER.last()
}
