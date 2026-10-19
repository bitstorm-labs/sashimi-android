package dev.bitstorm.sashimi.core.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSocket(
    val target: RemoteTarget,
    val onEvent: (SocketEvent) -> Unit,
) : RemoteSocket {
    val sent = mutableListOf<String>()
    var closed = false

    override fun send(text: String): Boolean {
        sent += text
        return true
    }

    override fun close() {
        closed = true
    }

    fun open() = onEvent(SocketEvent.Opened)

    fun receive(text: String) = onEvent(SocketEvent.Message(text))

    fun drop() = onEvent(SocketEvent.Closed)
}

private class FakeConnector : RemoteSocketConnector {
    val sockets = mutableListOf<FakeSocket>()
    val connectTimes = mutableListOf<Long>()
    var clock: () -> Long = { 0L }

    /** When set, every handshake is refused the moment it is attempted. */
    var refuse = false

    override fun connect(
        target: RemoteTarget,
        onEvent: (SocketEvent) -> Unit,
    ): RemoteSocket {
        connectTimes += clock()
        val socket = FakeSocket(target, onEvent).also { sockets += it }
        if (refuse) socket.drop()
        return socket
    }
}

private const val STOP = """{"MessageType":"Playstate","Data":{"Command":"Stop"}}"""
private const val PAUSE = """{"MessageType":"Playstate","Data":{"Command":"Pause"}}"""
private const val HELLO =
    """{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"Header":"H","Text":"hello","TimeoutMs":"4000"}}}"""
private const val KEEP_ALIVE_60 = """{"MessageType":"ForceKeepAlive","Data":60}"""

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlConnectionTest {
    private val connector = FakeConnector()
    private val keys = MutableStateFlow<RemoteSessionKey?>(RemoteSessionKey("s1", "u1"))
    private var token = "t1"
    private var target: RemoteTarget? = null
    private var registrations = 0
    private var registrationFails = false
    private var registrationGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private fun connection() =
        RemoteControlConnection(
            connector = connector,
            resolveTarget = { target ?: RemoteTarget("http://server/socket?deviceId=d", "MediaBrowser Token=\"$token\"") },
            registerCapabilities = {
                registrations++
                registrationGate?.await()
                if (registrationFails) error("capabilities POST failed")
            },
            backoff = ReconnectBackoff(jitter = { 0.0 }),
        )

    private fun TestScope.started(): RemoteControlConnection =
        connection().also { c ->
            connector.clock = { testScheduler.currentTime }
            backgroundScope.launch { c.run(keys) }
            runCurrent()
        }

    @Test
    fun `opening the socket registers capabilities and only then reads as connected`() =
        runTest {
            val c = started()
            assertEquals(RemoteControlConnection.State.CONNECTING, c.state.value)
            assertEquals(0, registrations)

            connector.sockets.single().open()
            runCurrent()

            assertEquals(1, registrations)
            assertEquals(RemoteControlConnection.State.CONNECTED, c.state.value)
        }

    @Test
    fun `Stop and DisplayMessage are surfaced and everything else is dropped`() =
        runTest {
            val c = started()
            val got = mutableListOf<RemoteCommand>()
            backgroundScope.launch { c.commands.collect { got += it } }
            runCurrent()
            val socket = connector.sockets.single()
            socket.open()
            socket.receive(PAUSE)
            socket.receive(STOP)
            socket.receive("garbage")
            socket.receive(HELLO)
            runCurrent()

            assertEquals(listOf(RemoteCommand.Stop, RemoteCommand.DisplayMessage("H", "hello", 4_000)), got)
            assertFalse("an ignored frame must not end the session", socket.closed)
        }

    @Test
    fun `keep-alives go out at half the server's window, not before`() =
        runTest {
            started()
            val socket = connector.sockets.single()
            socket.open()
            socket.receive(KEEP_ALIVE_60)
            runCurrent()

            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(emptyList<String>(), socket.sent)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(RemoteProtocol.KEEP_ALIVE), socket.sent)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, socket.sent.size)
        }

    @Test
    fun `a dropped socket is closed, its keep-alive stops, and it reconnects after the base delay`() =
        runTest {
            val c = started()
            val first = connector.sockets.single()
            first.open()
            first.receive(KEEP_ALIVE_60)
            runCurrent()

            first.drop()
            runCurrent()
            assertTrue(first.closed)
            assertEquals(RemoteControlConnection.State.WAITING, c.state.value)

            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, connector.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, connector.sockets.size)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("the dead socket's keep-alive must not keep firing", emptyList<String>(), first.sent)
        }

    @Test
    fun `refused handshakes back off exponentially`() =
        runTest {
            connector.refuse = true
            started()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), connector.connectTimes)
        }

    @Test
    fun `a healthy session resets the failure count`() =
        runTest {
            connector.refuse = true
            started()
            advanceTimeBy(7_000) // refused at 0, 1 s, 3 s, 7 s: the next wait would be 8 s
            runCurrent()
            connector.refuse = false
            advanceTimeBy(8_000) // t=15 s: connects
            runCurrent()
            connector.sockets.last().open()
            runCurrent()
            connector.refuse = true
            connector.sockets.last().drop() // healthy session ends at t=15 s
            runCurrent()
            advanceTimeBy(1_000) // t=16 s: refused, first failure since the healthy one
            runCurrent()
            advanceTimeBy(1_000) // so the retry is 1 s later, not 16 s
            runCurrent()
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 16_000L, 17_000L), connector.connectTimes)
        }

    @Test
    fun `the session is not connected until capabilities are registered`() =
        runTest {
            registrationGate = kotlinx.coroutines.CompletableDeferred()
            val c = started()
            connector.sockets.single().open()
            runCurrent()
            assertEquals(1, registrations)
            assertEquals(RemoteControlConnection.State.CONNECTING, c.state.value)

            registrationGate!!.complete(Unit)
            runCurrent()
            assertEquals(RemoteControlConnection.State.CONNECTED, c.state.value)
        }

    @Test
    fun `failed registration closes the socket and retries instead of sitting uncontrollable`() =
        runTest {
            registrationFails = true
            val c = started()
            val socket = connector.sockets.single()
            socket.open()
            runCurrent()

            assertTrue(socket.closed)
            assertFalse(c.state.value == RemoteControlConnection.State.CONNECTED)

            registrationFails = false
            advanceTimeBy(1_000)
            runCurrent()
            connector.sockets.last().open()
            runCurrent()
            assertEquals(RemoteControlConnection.State.CONNECTED, c.state.value)
        }

    @Test
    fun `a null key closes the socket and opens nothing more`() =
        runTest {
            val c = started()
            val socket = connector.sockets.single()
            socket.open()
            runCurrent()

            keys.value = null
            runCurrent()
            assertTrue(socket.closed)
            assertEquals(RemoteControlConnection.State.IDLE, c.state.value)

            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, connector.sockets.size)
        }

    @Test
    fun `switching server or user closes the old socket and opens a new one at once`() =
        runTest {
            started()
            val first = connector.sockets.single()
            first.open()
            runCurrent()

            keys.value = RemoteSessionKey("s2", "u9")
            runCurrent()

            assertTrue(first.closed)
            assertEquals(2, connector.sockets.size)
            assertFalse(connector.sockets.last().closed)
        }

    @Test
    fun `every attempt resolves the target afresh, so a new token is used on reconnect`() =
        runTest {
            started()
            connector.sockets.single().open()
            runCurrent()
            token = "t2"
            connector.sockets.single().drop()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                "MediaBrowser Token=\"t2\"",
                connector.sockets
                    .last()
                    .target.authorization,
            )
        }

    @Test
    fun `stopping the runner closes the socket and reads idle`() =
        runTest {
            val c = connection()
            val job = launch { c.run(keys) }
            runCurrent()
            connector.sockets.single().open()
            runCurrent()

            job.cancel()
            runCurrent()

            assertTrue(connector.sockets.single().closed)
            assertEquals(RemoteControlConnection.State.IDLE, c.state.value)
        }

    @Test
    fun `a command with no listener is dropped, not replayed to a later one`() =
        runTest {
            val c = started()
            val socket = connector.sockets.single()
            socket.open()
            socket.receive(STOP)
            runCurrent()

            val got = mutableListOf<RemoteCommand>()
            backgroundScope.launch { c.commands.collect { got += it } }
            runCurrent()
            assertEquals(emptyList<RemoteCommand>(), got)
        }

    @Test
    fun `the target never prints its token`() {
        val t = RemoteTarget("http://server/socket", "MediaBrowser Token=\"secret-token\"")
        assertFalse("secret-token" in t.toString())
    }
}

class RemoteSessionKeyTest {
    @Test
    fun `a key only when signed in, wanted and online`() {
        assertEquals(RemoteSessionKey("s", "u"), RemoteSessionKey.of(true, "s", "u", wanted = true, online = true))
        assertEquals(null, RemoteSessionKey.of(false, "s", "u", wanted = true, online = true))
        assertEquals(null, RemoteSessionKey.of(true, null, "u", wanted = true, online = true))
        assertEquals(null, RemoteSessionKey.of(true, "s", null, wanted = true, online = true))
        assertEquals(null, RemoteSessionKey.of(true, "s", "u", wanted = false, online = true))
        assertEquals(
            "offline: nothing to retry, and coming back reconnects at once",
            null,
            RemoteSessionKey.of(true, "s", "u", wanted = true, online = false),
        )
    }
}

class ReconnectBackoffTest {
    @Test
    fun `doubles from one second and caps at a minute`() {
        val b = ReconnectBackoff(jitter = { 0.0 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..7).map(b::delayMs))
        assertEquals(60_000L, b.delayMs(500))
    }

    @Test
    fun `jitter adds at most a fifth`() {
        val b = ReconnectBackoff(jitter = { 1.0 })
        assertEquals(1_200L, b.delayMs(0))
        assertEquals(72_000L, b.delayMs(10))
        assertEquals("out-of-range jitter is clamped", 1_200L, ReconnectBackoff(jitter = { 7.0 }).delayMs(0))
    }
}
