package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ep(
    id: String,
    seasonId: String = "s1",
) = BaseItemDto(id = id, name = id, type = ItemType.EPISODE, seriesId = "show", seasonId = seasonId)

private fun season(id: String) = BaseItemDto(id = id, name = id, type = ItemType.SEASON)

/** Applies [events] in order, returning the final step. */
private fun UpNextState.after(vararg events: UpNextEvent): UpNextStep {
    var step = UpNextStep(this)
    for (e in events) step = UpNext.reduce(step.state, e)
    return step
}

class UpNextTest {
    // MARK: - Show / suppress

    @Test
    fun `autoplay on shows the screen rather than advancing silently`() {
        assertEquals(EndOfItemAction.SHOW_UP_NEXT, UpNext.onEnded(hasNext = true, autoPlay = true, inPip = false))
    }

    @Test
    fun `autoplay off still shows the screen when there is a next episode`() {
        assertEquals(EndOfItemAction.SHOW_UP_NEXT, UpNext.onEnded(hasNext = true, autoPlay = false, inPip = false))
    }

    @Test
    fun `no next episode leaves the player`() {
        assertEquals(EndOfItemAction.EXIT, UpNext.onEnded(hasNext = false, autoPlay = true, inPip = false))
        assertEquals(EndOfItemAction.EXIT, UpNext.onEnded(hasNext = false, autoPlay = false, inPip = true))
    }

    @Test
    fun `picture in picture never shows the screen`() {
        assertEquals(EndOfItemAction.AUTO_ADVANCE, UpNext.onEnded(hasNext = true, autoPlay = true, inPip = true))
        assertEquals(EndOfItemAction.EXIT, UpNext.onEnded(hasNext = true, autoPlay = false, inPip = true))
    }

    // MARK: - Countdown

    @Test
    fun `autoplay starts a ten second countdown, off has none`() {
        val on = UpNext.start(ep("e2"), autoPlay = true)
        assertEquals(10_000L, on.remainingMs)
        assertEquals(10, on.secondsRemaining)
        assertTrue(on.countdownRunning)

        val off = UpNext.start(ep("e2"), autoPlay = false)
        assertNull(off.remainingMs)
        assertNull(off.secondsRemaining)
        assertFalse(off.countdownRunning)
        assertEquals(0f, off.progress)
    }

    @Test
    fun `ticks sweep the fill and play at zero`() {
        val start = UpNext.start(ep("e2"), autoPlay = true)
        val half = start.after(UpNextEvent.Tick(5_000))
        assertEquals(0.5f, half.state.progress, 0.001f)
        assertEquals(5, half.state.secondsRemaining)
        assertFalse(half.playNow)

        val done = half.state.after(UpNextEvent.Tick(4_900), UpNextEvent.Tick(200))
        assertTrue(done.playNow)
        assertEquals(0L, done.state.remainingMs)
        assertEquals(1f, done.state.progress)
    }

    @Test
    fun `seconds round up so the last second reads 1`() {
        val s = UpNext.start(ep("e2"), autoPlay = true).after(UpNextEvent.Tick(9_100)).state
        assertEquals(1, s.secondsRemaining)
    }

    @Test
    fun `no countdown never plays by itself`() {
        val step = UpNext.start(ep("e2"), autoPlay = false).after(UpNextEvent.Tick(60_000))
        assertFalse(step.playNow)
    }

    @Test
    fun `background pauses the countdown and foreground resumes it`() {
        val paused = UpNext.start(ep("e2"), autoPlay = true).after(UpNextEvent.Tick(3_000), UpNextEvent.Pause, UpNextEvent.Tick(30_000))
        assertFalse(paused.playNow)
        assertEquals(7_000L, paused.state.remainingMs)

        val resumed = paused.state.after(UpNextEvent.Resume, UpNextEvent.Tick(2_000))
        assertEquals(5_000L, resumed.state.remainingMs)
        assertTrue(resumed.state.countdownRunning)
    }

    @Test
    fun `cancel stops the countdown for good`() {
        val step = UpNext.start(ep("e2"), autoPlay = true).after(UpNextEvent.Tick(2_000), UpNextEvent.Cancel, UpNextEvent.Tick(20_000))
        assertTrue(step.state.cancelled)
        assertFalse(step.playNow)
        assertNull(step.state.secondsRemaining)
        assertEquals(0f, step.state.progress)
    }

    // MARK: - Skip

    @Test
    fun `skip does nothing until the following episode is known`() {
        val s = UpNext.start(ep("e2"), autoPlay = true)
        assertFalse(s.canSkip)
        // Kept on screen meanwhile, so the buttons do not jump.
        assertTrue(s.showSkip)
        // Pressing it anyway changes nothing.
        assertEquals(s, s.after(UpNextEvent.Skip).state)
    }

    @Test
    fun `skip is hidden when there is nothing after`() {
        val s = UpNext.start(ep("e2"), autoPlay = true).after(UpNextEvent.FollowingResolved("e2", null)).state
        assertEquals(FollowingEpisode.None, s.following)
        assertFalse(s.canSkip)
        assertFalse(s.showSkip)
    }

    @Test
    fun `skip shows the following episode and restarts the countdown`() {
        val s =
            UpNext.start(ep("e2"), autoPlay = true)
                .after(UpNextEvent.Tick(6_000), UpNextEvent.FollowingResolved("e2", ep("e3")), UpNextEvent.Skip)
                .state
        assertEquals("e3", s.episode.id)
        assertEquals(10_000L, s.remainingMs)
        assertEquals(FollowingEpisode.Pending, s.following)
        assertFalse(s.canSkip)
    }

    @Test
    fun `skip is repeatable`() {
        val s =
            UpNext.start(ep("e2"), autoPlay = true)
                .after(
                    UpNextEvent.FollowingResolved("e2", ep("e3")),
                    UpNextEvent.Skip,
                    UpNextEvent.FollowingResolved("e3", ep("e4")),
                    UpNextEvent.Skip,
                ).state
        assertEquals("e4", s.episode.id)
    }

    @Test
    fun `skip without autoplay keeps no countdown`() {
        val s =
            UpNext.start(ep("e2"), autoPlay = false)
                .after(UpNextEvent.FollowingResolved("e2", ep("e3")), UpNextEvent.Skip)
                .state
        assertEquals("e3", s.episode.id)
        assertNull(s.remainingMs)
    }

    @Test
    fun `a stale lookup for a skipped-past episode is ignored`() {
        val s =
            UpNext.start(ep("e2"), autoPlay = true)
                .after(UpNextEvent.FollowingResolved("e2", ep("e3")), UpNextEvent.Skip, UpNextEvent.FollowingResolved("e2", ep("e3")))
                .state
        assertEquals("e3", s.episode.id)
        assertEquals(FollowingEpisode.Pending, s.following)
    }

    @Test
    fun `skip does nothing once cancelled`() {
        val s =
            UpNext.start(ep("e2"), autoPlay = true)
                .after(UpNextEvent.FollowingResolved("e2", ep("e3")), UpNextEvent.Cancel, UpNextEvent.Skip)
                .state
        assertEquals("e2", s.episode.id)
    }

    // MARK: - PiP

    @Test
    fun `entering pip mid-countdown plays at once`() {
        assertTrue(UpNext.start(ep("e2"), autoPlay = true).after(UpNextEvent.EnteredPip).playNow)
    }

    @Test
    fun `entering pip without a countdown waits`() {
        assertFalse(UpNext.start(ep("e2"), autoPlay = false).after(UpNextEvent.EnteredPip).playNow)
        assertFalse(UpNext.start(ep("e2"), autoPlay = true).after(UpNextEvent.Cancel, UpNextEvent.EnteredPip).playNow)
    }

    // MARK: - Skip advancement (season rollover)

    private val seasons = listOf(season("s1"), season("s2"))
    private val episodes =
        mapOf(
            "s1" to listOf(ep("e1"), ep("e2")),
            "s2" to listOf(ep("f1", "s2"), ep("f2", "s2")),
        )

    private suspend fun resolve(
        current: BaseItemDto,
        online: Boolean = true,
        fallback: suspend (String) -> BaseItemDto? = { null },
    ) = AutoPlayNextResolver.resolve(
        current,
        episodesOf = { _, season -> if (online) episodes[season].orEmpty() else emptyList() },
        seasonsOf = { if (online) seasons else emptyList() },
        fallback = fallback,
    )

    @Test
    fun `resolve moves within a season`() =
        runTest {
            assertEquals("e2", resolve(ep("e1"))?.id)
        }

    @Test
    fun `resolve rolls over to the next season`() =
        runTest {
            assertEquals("f1", resolve(ep("e2"))?.id)
        }

    @Test
    fun `resolve is null after the last episode of the last season`() =
        runTest {
            assertNull(resolve(ep("f2", "s2")))
        }

    @Test
    fun `resolve falls back to the downloads when the server cannot answer`() =
        runTest {
            // Offline every fetch comes back empty. The downloads fallback used
            // to be reachable only when the seasons call SUCCEEDED, so it never
            // ran offline, which is the one time it is for.
            assertEquals("f1", resolve(ep("e2"), online = false, fallback = { ep("f1", "s2") })?.id)
        }

    @Test
    fun `resolve ignores non-episodes`() =
        runTest {
            assertNull(resolve(BaseItemDto(id = "m", type = ItemType.MOVIE)))
        }
}
