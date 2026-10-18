package dev.bitstorm.sashimi.core.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

/**
 * Where to open the socket and how to authenticate it. Resolved fresh for every
 * attempt, so a re-sign-in's new token is picked up by the next reconnect.
 */
class RemoteTarget(
    val socketUrl: String,
    val authorization: String,
) {
    // Never print the token: this object ends up in exceptions and debuggers.
    override fun toString(): String = "RemoteTarget($socketUrl)"
}

/** One open (or opening) socket. */
interface RemoteSocket {
    fun send(text: String): Boolean

    fun close()
}

sealed interface SocketEvent {
    data object Opened : SocketEvent

    data class Message(val text: String) : SocketEvent

    /** The socket is gone: closed by either side or failed (including a refused handshake). */
    data object Closed : SocketEvent
}

/** Opens a socket and reports its life through [onEvent], from any thread. */
fun interface RemoteSocketConnector {
    fun connect(
        target: RemoteTarget,
        onEvent: (SocketEvent) -> Unit,
    ): RemoteSocket
}

/**
 * Exponential reconnect delay: 1 s, 2 s, 4 s ... capped at 60 s, plus up to 20 %
 * jitter so a server restart is not met by every client in the same instant.
 * [attempt] counts consecutive failures; 0 is the first retry after a healthy
 * session ended.
 */
class ReconnectBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    fun delayMs(attempt: Int): Long {
        val exp = baseMs * (1L shl min(attempt, 30).coerceAtLeast(0))
        val capped = min(exp, maxMs)
        return capped + (capped * JITTER_FRACTION * jitter().coerceIn(0.0, 1.0)).toLong()
    }

    private companion object {
        const val JITTER_FRACTION = 0.2
    }
}

/** Who the socket belongs to. A change (server switch, other user) reconnects. */
data class RemoteSessionKey(
    val serverId: String,
    val userId: String,
) {
    companion object {
        /**
         * The socket to hold, or null for none. Offline counts as none: there
         * is nothing to retry against, and the null-to-key edge when the
         * network returns reconnects at once. Without it a link that died
         * silently (no FIN, e.g. airplane mode) stayed "open" until the next
         * failed ping, up to a minute in which commands were lost (#161).
         */
        fun of(
            authenticated: Boolean,
            serverId: String?,
            userId: String?,
            wanted: Boolean,
            online: Boolean,
        ): RemoteSessionKey? =
            if (authenticated && wanted && online && serverId != null && userId != null) RemoteSessionKey(serverId, userId) else null
    }
}

/**
 * Keeps the Jellyfin session socket open while [run]'s key flow says so, and
 * turns its frames into [commands].
 *
 * One session: connect, register capabilities (the server only treats the
 * session as controllable once both are in place, and it forgets capabilities
 * when it drops the session, so they are re-sent on every open), answer the
 * server's ForceKeepAlive, and surface Stop / DisplayMessage. When the socket
 * drops it reconnects with [backoff]; a session that got as far as registering
 * resets the failure count.
 */
class RemoteControlConnection(
    private val connector: RemoteSocketConnector,
    private val resolveTarget: () -> RemoteTarget?,
    private val registerCapabilities: suspend () -> Unit,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
) {
    enum class State { IDLE, CONNECTING, CONNECTED, WAITING }

    private val _state = MutableStateFlow(State.IDLE)

    /** CONNECTED means open AND capabilities registered: the dashboard can control us. */
    val state: StateFlow<State> = _state.asStateFlow()

    // A command nobody is listening for (a Stop with no player open) is dropped,
    // never replayed later onto a player that opens afterwards.
    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: SharedFlow<RemoteCommand> = _commands.asSharedFlow()

    /** Runs until cancelled. A null key closes the socket; a new key reconnects. */
    suspend fun run(keys: Flow<RemoteSessionKey?>) {
        try {
            keys.distinctUntilChanged().collectLatest { key ->
                if (key == null) {
                    _state.value = State.IDLE
                } else {
                    maintain()
                }
            }
        } finally {
            _state.value = State.IDLE
        }
    }

    private suspend fun maintain() {
        var failures = 0
        while (true) {
            val target = resolveTarget()
            val registered = target != null && session(target)
            failures = if (registered) 0 else failures + 1
            _state.value = State.WAITING
            // After a healthy session the first retry is the base delay, not zero:
            // a server that accepts and immediately drops must not be hammered.
            delay(backoff.delayMs(if (registered) 0 else failures - 1))
        }
    }

    /** One socket's life. True when it got as far as registering capabilities. */
    private suspend fun session(target: RemoteTarget): Boolean =
        coroutineScope {
            _state.value = State.CONNECTING
            val events = Channel<SocketEvent>(Channel.UNLIMITED)
            val socket = connector.connect(target) { events.trySend(it) }
            var registered = false
            var keepAlive: Job? = null
            try {
                for (event in events) {
                    when (event) {
                        SocketEvent.Opened -> {
                            if (!register()) break
                            registered = true
                            _state.value = State.CONNECTED
                        }
                        is SocketEvent.Message ->
                            when (val inbound = RemoteProtocol.parse(event.text)) {
                                is Inbound.ForceKeepAlive -> {
                                    keepAlive?.cancel()
                                    keepAlive =
                                        launch {
                                            while (isActive) {
                                                delay(inbound.intervalMs)
                                                socket.send(RemoteProtocol.KEEP_ALIVE)
                                            }
                                        }
                                }
                                is Inbound.Command -> _commands.tryEmit(inbound.command)
                                Inbound.Ignored -> Unit
                            }
                        SocketEvent.Closed -> break
                    }
                }
                registered
            } finally {
                keepAlive?.cancel()
                socket.close()
            }
        }

    /**
     * False when registration failed. The socket is then closed and retried:
     * an open socket the server will not route commands to is worse than a
     * visible reconnect, because the dashboard would still hide the buttons.
     */
    private suspend fun register(): Boolean =
        try {
            registerCapabilities()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
}
