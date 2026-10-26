package dev.bitstorm.sashimi.core.util

/**
 * "Refresh when the screen comes back, but not on the first resume (which
 * coincides with the initial load)". Owned by the screen's ViewModel, NOT
 * remembered in composition (#186): Navigation disposes a destination's
 * composition when another screen is pushed over it, so a `remember`ed
 * first-resume flag was true again on every return and the refresh never ran.
 * The series page kept stale watched marks; Home kept a stale Continue Watching.
 */
class ResumeRefreshGate {
    private var seenFirst = false

    /** True when this resume should refresh. */
    @Synchronized
    fun onResume(): Boolean {
        if (seenFirst) return true
        seenFirst = true
        return false
    }
}
