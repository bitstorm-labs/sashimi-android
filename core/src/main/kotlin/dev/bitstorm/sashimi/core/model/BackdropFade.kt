package dev.bitstorm.sashimi.core.model

/**
 * How a backdrop (and the hero trailer drawn in the same frame) fades into
 * the page, as fractions of the backdrop's own frame. One place for every
 * backdrop surface, so they cannot drift apart. Top and right edges never
 * fade: they run off the screen.
 */
object BackdropFade {
    /** The left edge is transparent at 0 and opaque from this fraction of the width. */
    const val LEFT_FADE_END = 0.35f

    /** Opaque down to this fraction of the height, then transparent at the bottom. */
    const val BOTTOM_FADE_START = 0.6f
}
