package dev.bitstorm.sashimi.core.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlPresenceTest {
    private val presence = RemoteControlPresence(graceMs = 15_000)

    @Test
    fun `the policy connects in the foreground or while playing`() {
        assertFalse(RemoteControlPresence.shouldConnect(foreground = false, playing = false))
        assertTrue(RemoteControlPresence.shouldConnect(foreground = true, playing = false))
        assertTrue(RemoteControlPresence.shouldConnect(foreground = false, playing = true))
        assertTrue(RemoteControlPresence.shouldConnect(foreground = true, playing = true))
    }

    @Test
    fun `coming to the foreground connects at once, leaving disconnects only after the grace`() =
        runTest {
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { presence.wanted.collect { seen += it } }
            presence.setForeground(true)
            runCurrent()
            assertEquals(listOf(true), seen)

            presence.setForeground(false)
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals(listOf(true), seen)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(true, false), seen)
        }

    @Test
    fun `a quick trip away and back never disconnects`() =
        runTest {
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { presence.wanted.collect { seen += it } }
            presence.setForeground(true)
            runCurrent()
            presence.setForeground(false)
            advanceTimeBy(5_000)
            presence.setForeground(true)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf(true), seen)
        }

    @Test
    fun `playback keeps the socket wanted in the background, per player`() =
        runTest {
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { presence.wanted.collect { seen += it } }
            val a = Any()
            val b = Any()
            presence.setPlaying(a, true)
            presence.setPlaying(b, true)
            presence.setPlaying(a, false)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("b is still playing", listOf(true), seen)

            presence.setPlaying(b, false)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(listOf(true, false), seen)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteMessageBoardTest {
    private fun msg(
        text: String,
        ms: Long,
        header: String? = null,
    ) = RemoteCommand.DisplayMessage(header, text, ms)

    @Test
    fun `a message shows at once and clears after its own timeout`() =
        runTest {
            val board = RemoteMessageBoard(backgroundScope)
            board.show(msg("hi", 4_000, header = "Admin"))
            runCurrent()
            assertEquals("hi", board.current.value?.text)
            assertEquals("Admin", board.current.value?.header)

            advanceTimeBy(3_999)
            runCurrent()
            assertEquals("hi", board.current.value?.text)
            advanceTimeBy(1)
            runCurrent()
            assertNull(board.current.value)
        }

    @Test
    fun `a newer message replaces the old one and the old timer cannot clear it`() =
        runTest {
            val board = RemoteMessageBoard(backgroundScope)
            board.show(msg("first", 4_000))
            advanceTimeBy(3_000)
            board.show(msg("second", 4_000))
            runCurrent()

            advanceTimeBy(1_500) // first's deadline has passed
            runCurrent()
            assertEquals("second", board.current.value?.text)

            advanceTimeBy(2_500)
            runCurrent()
            assertNull(board.current.value)
        }

    @Test
    fun `the same text sent twice is a new message`() =
        runTest {
            val board = RemoteMessageBoard(backgroundScope)
            board.show(msg("same", 4_000))
            val first = board.current.value
            board.show(msg("same", 4_000))
            assertTrue(first != board.current.value)
        }

    @Test
    fun `dismiss clears early`() =
        runTest {
            val board = RemoteMessageBoard(backgroundScope)
            board.show(msg("hi", 60_000))
            board.dismiss()
            assertNull(board.current.value)
        }
}
