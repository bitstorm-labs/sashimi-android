package dev.bitstorm.sashimi.core.model

/**
 * How a backdrop (and the hero trailer drawn in the same frame) fades into
 * the page, as fractions of the backdrop's own frame. One place for every
 * backdrop surface, so they cannot drift apart. The right edge never fades:
 * it runs off the screen. Values are the owner-approved "Lightest" option
 * (#203): the old 35% / 60% fades hid artwork near the bottom.
 */
object BackdropFade {
    /** The left edge is transparent at 0 and opaque from this fraction of the width. */
    const val LEFT_FADE_END = 0.10f

    /**
     * Where a top edge that does not run off the screen (a phone hero under
     * its solid top bar, #208) becomes opaque: the same as the left edge.
     */
    const val TOP_FADE_END = LEFT_FADE_END

    /** Opaque down to this fraction of the height, then transparent at the bottom. */
    const val BOTTOM_FADE_START = 0.92f

    /**
     * The vertical alpha mask, top to bottom, as (fraction of the height,
     * opaque?) stops. The bottom always fades; the top only when [fadeTop].
     */
    fun verticalStops(fadeTop: Boolean): List<Pair<Float, Boolean>> =
        buildList {
            if (fadeTop) {
                add(0f to false)
                add(TOP_FADE_END to true)
            }
            add(BOTTOM_FADE_START to true)
            add(1f to false)
        }
}
