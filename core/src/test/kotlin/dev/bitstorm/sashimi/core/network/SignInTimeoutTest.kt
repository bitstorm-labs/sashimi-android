package dev.bitstorm.sashimi.core.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Drives the real OkHttp stack against loopback sockets, so the sign-in budget
 * and the failure mapping are checked against what OkHttp actually throws.
 */
class SignInTimeoutTest {
    private val sockets = mutableListOf<Socket>()
    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        runCatching { server?.close() }
    }

    /** Accepts connections and never answers: "reachable but not answering". */
    private fun stallingServer(): String {
        val s = ServerSocket(0).also { server = it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                runCatching { s.accept() }.getOrNull()?.let { synchronized(sockets) { sockets += it } }
            }
        }
        return "http://127.0.0.1:${s.localPort}"
    }

    /** Answers every request with [status] and an empty body. */
    private fun respondingServer(status: String): String {
        val s = ServerSocket(0).also { server = it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                runCatching {
                    val input = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { input.read() }
                    socket.getOutputStream().write(
                        "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    socket.close()
                }
            }
        }
        return "http://127.0.0.1:${s.localPort}"
    }

    /** A plain-HTTP server that answers the first bytes it gets, as a real one answers a TLS ClientHello. */
    private fun plainHttpServer(): String {
        val s = ServerSocket(0).also { server = it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                runCatching {
                    socket.getInputStream().read(ByteArray(1024))
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    socket.close()
                }
            }
        }
        return "http://127.0.0.1:${s.localPort}"
    }

    private fun client(timeoutMillis: Long = 500) = JellyfinClient(deviceId = "test-device", signInTimeoutMillis = timeoutMillis)

    private fun failureOf(block: suspend () -> Unit): Throwable =
        runBlocking {
            try {
                block()
                fail("expected the sign-in step to fail")
                error("unreachable")
            } catch (e: Exception) {
                e
            }
        }

    @Test
    fun `a stalled probe fails within the sign-in budget, without retrying`() {
        val client = client().apply { configure(stallingServer()) }
        val started = System.nanoTime()
        val error = failureOf { client.getPublicSystemInfo() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(SignInMessages.TIMED_OUT, signInFailureMessage(error))
        // One 500ms attempt. The browsing budget (30s read, 3 retries) would take minutes.
        assertTrue("took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `a stalled authenticate fails within the sign-in budget`() {
        val client = client().apply { configure(stallingServer()) }
        val started = System.nanoTime()
        val error = failureOf { client.authenticate("user", "pw") }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(SignInMessages.TIMED_OUT, signInFailureMessage(error))
        assertTrue("took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `a closed port reports the connection as refused`() {
        val port = ServerSocket(0).use { it.localPort } // bound then released: nothing listens
        val client = client().apply { configure("http://127.0.0.1:$port") }
        val error = failureOf { client.getPublicSystemInfo() }

        assertEquals(SignInMessages.REFUSED, signInFailureMessage(error))
    }

    @Test
    fun `a 401 on authenticate is wrong username or password`() {
        val client = client().apply { configure(respondingServer("401 Unauthorized")) }
        val error = failureOf { client.authenticate("user", "wrong") }

        assertEquals(SignInMessages.WRONG_CREDENTIALS, signInFailureMessage(error))
    }

    @Test
    fun `speaking https to a plain http server is a certificate rejection`() {
        val url = plainHttpServer().replace("http://", "https://")
        val client = client().apply { configure(url) }
        val error = failureOf { client.getPublicSystemInfo() }

        assertEquals(SignInMessages.CERTIFICATE_REJECTED, signInFailureMessage(error))
    }
}
