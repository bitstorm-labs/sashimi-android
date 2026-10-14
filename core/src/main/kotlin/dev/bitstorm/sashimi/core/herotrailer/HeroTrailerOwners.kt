package dev.bitstorm.sashimi.core.herotrailer

/**
 * Which detail screen owns the single hero-trailer player, and whether a
 * trailer may (re)start. Port of the Apple `HeroTrailerOwners`.
 *
 * Every detail destination on the back stack is an owner. The newest one to
 * appear owns the player. A trailer plays at most once per source per visit:
 * drilling from a series into its season hands the running trailer over
 * instead of restarting it, and a trailer that already finished does not play
 * again until every detail screen has been dismissed.
 *
 * Android adds [hidden]. Navigation Compose disposes a destination when
 * something is pushed over it, so "covered by the player, a person page or
 * another tab" arrives as the screen leaving composition rather than as a
 * covering screen appearing (Apple's `coveringScreenAppeared`). A detail pushed
 * over a detail composes before the one underneath is disposed, which is why
 * [hidden] only stops the player when the hidden screen is still the top owner.
 *
 * Not thread-safe by design: the owning controller confines every call to the
 * main thread.
 */
class HeroTrailerOwners {
    sealed interface Decision {
        /** Start the trailer for this source in the top screen. */
        data class Start(val sourceId: String) : Decision

        /** Keep the running trailer; it now shows in the top screen. */
        data object HandOff : Decision

        /** Stop whatever is playing; the top screen shows its image. */
        data object Stop : Decision

        /** Nothing changes. */
        data object Ignore : Decision
    }

    private data class Entry(val owner: String, val sourceId: String?)

    private val stack = mutableListOf<Entry>()
    private val played = mutableSetOf<String>()

    /** The screen that should currently show the trailer. */
    val topOwner: String? get() = stack.lastOrNull()?.owner

    /**
     * [owner] is showing. [sourceId] is null when it never plays a trailer (or is
     * not eligible right now); [playingSourceId] is the trailer running now.
     */
    fun appeared(
        owner: String,
        sourceId: String?,
        playingSourceId: String?,
    ): Decision {
        stack.removeAll { it.owner == owner }
        stack.add(Entry(owner, sourceId))
        if (sourceId == null) return Decision.Stop
        if (sourceId == playingSourceId) return Decision.HandOff
        if (!played.add(sourceId)) return Decision.Stop
        return Decision.Start(sourceId)
    }

    /** [owner] left the screen but is still on the back stack. */
    fun hidden(owner: String): Decision = if (owner == topOwner) Decision.Stop else Decision.Ignore

    /** [owner] left the back stack for good. */
    fun dismissed(
        owner: String,
        playingSourceId: String?,
    ): Decision {
        val index = stack.indexOfFirst { it.owner == owner }
        if (index < 0) return Decision.Ignore
        val wasTop = index == stack.lastIndex
        stack.removeAt(index)
        val top = stack.lastOrNull()
        if (top == null) {
            played.clear()
            return Decision.Stop
        }
        if (!wasTop) return Decision.Ignore
        if (playingSourceId != null && top.sourceId == playingSourceId) return Decision.HandOff
        return Decision.Stop
    }

    fun reset() {
        stack.clear()
        played.clear()
    }
}
