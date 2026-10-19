package dev.bitstorm.sashimi.core.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * [RemoteSocketConnector] on OkHttp's WebSocket.
 *
 * The REST client's call and read timeouts are safe to inherit: OkHttp
 * exempts an upgraded socket from both (pinned by a test, since the REST
 * client's 120 s call timeout would otherwise cut every session). [forSockets]
 * adds an OkHttp ping, which detects a dead link (Wi-Fi gone without a FIN)
 * far sooner than the server's keep-alive window lapsing.
 */
class OkHttpRemoteSocketConnector(
    private val client: OkHttpClient,
) : RemoteSocketConnector {
    override fun connect(
        target: RemoteTarget,
        onEvent: (SocketEvent) -> Unit,
    ): RemoteSocket {
        val request =
            Request
                .Builder()
                .url(target.socketUrl)
                .header(AUTHORIZATION_HEADER, target.authorization)
                .build()
        val socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = onEvent(SocketEvent.Opened)

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) = onEvent(SocketEvent.Message(text))

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        // Answer the server's close; onClosed then reports it.
                        webSocket.close(NORMAL_CLOSURE, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) = onEvent(SocketEvent.Closed)

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        response?.close()
                        onEvent(SocketEvent.Closed)
                    }
                },
            )
        return object : RemoteSocket {
            override fun send(text: String): Boolean = socket.send(text)

            override fun close() {
                if (!socket.close(NORMAL_CLOSURE, null)) socket.cancel()
            }
        }
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val NORMAL_CLOSURE = 1000
        private const val PING_SECONDS = 30L

        /** A copy of [base] (sharing its pool and dispatcher) fit for long-lived sockets. */
        fun forSockets(base: OkHttpClient): OkHttpRemoteSocketConnector =
            OkHttpRemoteSocketConnector(
                base
                    .newBuilder()
                    .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
    }
}
