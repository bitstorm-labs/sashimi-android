package dev.bitstorm.sashimi.core.remote

import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RemoteClientWiringTest {
    private class Recorder : Interceptor {
        var method: String? = null
        var path: String? = null
        var body: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            method = req.method
            path = req.url.encodedPath
            body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            return Response
                .Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("ok")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    @Test
    fun `capabilities are POSTed with every field present`() =
        runTest {
            // The client's JSON omits defaults, so a defaulted DTO would go out as {}.
            val rec = Recorder()
            val client = JellyfinClient(deviceId = "dev-1", httpClient = OkHttpClient.Builder().addInterceptor(rec).build())
            client.configure("http://server.local:9096", "tok", "u1")

            client.reportCapabilities()

            assertEquals("POST", rec.method)
            assertEquals("/Sessions/Capabilities/Full", rec.path)
            val body = Json.parseToJsonElement(rec.body!!).jsonObject
            assertEquals(true, body["SupportsMediaControl"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("DisplayMessage"), body["SupportedCommands"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(emptyList<String>(), body["PlayableMediaTypes"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `the socket target is the server's socket path with this device and a token header`() {
        val client = JellyfinClient(deviceId = "dev-1")
        assertNull("signed out: no socket", client.remoteTarget())

        client.configure("https://jf.example.com/jellyfin/", "tok", "u1")
        val target = client.remoteTarget()!!

        assertEquals("https://jf.example.com/jellyfin/socket?deviceId=dev-1", target.socketUrl)
        assertTrue(target.authorization.startsWith("MediaBrowser "))
        assertTrue("Token=\"tok\"" in target.authorization)
        assertTrue("DeviceId=\"dev-1\"" in target.authorization)
    }
}

/** The real OkHttp adapter against a local WebSocket server. */
class OkHttpRemoteSocketConnectorTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun serveSocket(onServerSocket: (WebSocket) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = onServerSocket(webSocket)

                    // A real server answers the close frame; without this the
                    // client waits out OkHttp's close timeout.
                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, null)
                    }
                },
            ),
        )
        server.start()
    }

    @Test
    fun `opens with the auth header, delivers frames both ways and reports the close`() {
        val serverSide = LinkedBlockingQueue<WebSocket>()
        serveSocket { serverSide.put(it) }
        val events = LinkedBlockingQueue<SocketEvent>()
        val connector = OkHttpRemoteSocketConnector.forSockets(OkHttpClient())

        val socket =
            connector.connect(RemoteTarget(server.url("/socket?deviceId=d").toString(), "MediaBrowser Token=\"tok\"")) { events.put(it) }

        assertEquals(SocketEvent.Opened, events.poll(5, TimeUnit.SECONDS))
        val handshake: RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("MediaBrowser Token=\"tok\"", handshake.getHeader("Authorization"))
        assertEquals("/socket?deviceId=d", handshake.path)

        val peer = serverSide.poll(5, TimeUnit.SECONDS)!!
        peer.send("""{"MessageType":"ForceKeepAlive","Data":60}""")
        assertEquals(SocketEvent.Message("""{"MessageType":"ForceKeepAlive","Data":60}"""), events.poll(5, TimeUnit.SECONDS))
        assertTrue(socket.send(RemoteProtocol.KEEP_ALIVE))

        peer.close(1000, null)
        assertEquals(SocketEvent.Closed, events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the REST client's call timeout does not cut a long-lived socket`() {
        serveSocket { }
        val events = LinkedBlockingQueue<SocketEvent>()
        val rest =
            OkHttpClient
                .Builder()
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()

        val socket =
            OkHttpRemoteSocketConnector
                .forSockets(
                    rest,
                ).connect(RemoteTarget(server.url("/socket").toString(), "x")) { events.put(it) }

        assertEquals(SocketEvent.Opened, events.poll(5, TimeUnit.SECONDS))
        assertNull("still open well past the REST budgets", events.poll(1_500, TimeUnit.MILLISECONDS))
        socket.close()
        assertEquals(SocketEvent.Closed, events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a refused handshake is reported as closed`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        val events = LinkedBlockingQueue<SocketEvent>()

        OkHttpRemoteSocketConnector
            .forSockets(
                OkHttpClient(),
            ).connect(RemoteTarget(server.url("/socket").toString(), "x")) { events.put(it) }

        assertEquals(SocketEvent.Closed, events.poll(5, TimeUnit.SECONDS))
    }
}
