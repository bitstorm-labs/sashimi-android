package dev.bitstorm.sashimi.core.themesong

import dev.bitstorm.sashimi.core.model.ItemType

/**
 * What a visit event asks the theme-song player to do. The visit state decides;
 * the player only executes.
 */
sealed interface ThemeVisitAction {
    /** Nothing changes — the show already had a detail screen open, or still does. */
    data object None : ThemeVisitAction

    /**
     * A show-visit just began. [replacing] is the show whose theme was playing
     * immediately before, or null when nothing was — the player uses it to pick
     * the show-change fade over a plain start.
     */
    data class Start(val seriesId: String, val replacing: String?) : ThemeVisitAction

    /** The show whose theme is playing has no detail screens left. */
    data object Stop : ThemeVisitAction
}

/**
 * Tracks how many detail screens are open per show, and derives "start the
 * theme" / "stop the theme" from that alone.
 *
 * The rule (ported from tvOS/iOS/Roku): key on the **series id**, not the
 * screen, and **count depth** — increment when a detail screen for that series
 * appears, decrement when one goes away, and only act on the 0↔1 edges. That
 * single rule gives both halves of the behaviour without special-casing either:
 *
 *  - Drilling from a series into one of its seasons or episodes takes the depth
 *    1 → 2, which is not an edge, so the theme keeps playing uninterrupted.
 *  - Returning from the video player is silent because the player is not a
 *    detail screen: pushing it never took the depth to 0, so popping it never
 *    takes it back to 1. There is no "resume" concept at all — a theme plays
 *    once per show-visit.
 *
 * Not thread-safe by design: the owning service confines every call to the main
 * dispatcher, and keeping this a plain map makes it pure, cheap and testable.
 */
class ThemeVisitState {
    private val openDetails = mutableMapOf<String, Int>()

    /** The show whose theme the player should currently be playing, if any. */
    var currentKey: String? = null
        private set

    /** A detail screen for [seriesId] became part of the back stack. */
    fun appeared(seriesId: String): ThemeVisitAction {
        val depth = (openDetails[seriesId] ?: 0) + 1
        openDetails[seriesId] = depth
        // Only the 0 -> 1 edge is a new visit. Deeper is drilling down.
        if (depth > 1) return ThemeVisitAction.None
        val previous = currentKey
        currentKey = seriesId
        return ThemeVisitAction.Start(seriesId, previous)
    }

    /** A detail screen for [seriesId] left the back stack for good. */
    fun disappeared(seriesId: String): ThemeVisitAction {
        val depth = openDetails[seriesId] ?: return ThemeVisitAction.None
        if (depth > 1) {
            openDetails[seriesId] = depth - 1
            return ThemeVisitAction.None
        }
        openDetails.remove(seriesId)
        // A show that was superseded by another one is not the one playing, so
        // its last screen closing must not silence the show that replaced it.
        if (currentKey != seriesId) return ThemeVisitAction.None
        currentKey = null
        return ThemeVisitAction.Stop
    }

    /** Open detail screens for [seriesId]. Exposed for tests and diagnostics. */
    fun depthOf(seriesId: String): Int = openDetails[seriesId] ?: 0

    fun reset() {
        openDetails.clear()
        currentKey = null
    }
}

/**
 * The theme key for an item: a series is its own key, a season or episode
 * inherits its parent series, and anything else (movies, videos, collections)
 * has none and therefore never plays a theme.
 */
fun themeKeyFor(
    type: ItemType?,
    itemId: String,
    seriesId: String?,
): String? =
    when (type) {
        ItemType.SERIES -> itemId
        ItemType.SEASON, ItemType.EPISODE -> seriesId
        else -> null
    }
