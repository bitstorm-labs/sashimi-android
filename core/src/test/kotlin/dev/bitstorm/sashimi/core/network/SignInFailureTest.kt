package dev.bitstorm.sashimi.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class SignInFailureTest {
    private fun message(error: Throwable) = signInFailureMessage(error)

    // The copy is shared verbatim with sashimi-apple and sashimi-roku.
    @Test
    fun `copy matches the cross-client spec`() {
        assertEquals(
            "Can't reach a server at that address. Check the address and that you're on the same network.",
            SignInMessages.UNREACHABLE,
        )
        assertEquals(
            "The server refused the connection. Check the port, and that Jellyfin is running.",
            SignInMessages.REFUSED,
        )
        assertEquals(
            "The server's security certificate was rejected. " +
                "Check whether the address should start with http:// or https://.",
            SignInMessages.CERTIFICATE_REJECTED,
        )
        assertEquals(
            "The server didn't answer within 12 seconds. It may be down or busy. Try again.",
            SignInMessages.TIMED_OUT,
        )
        assertEquals("Wrong username or password.", SignInMessages.WRONG_CREDENTIALS)
    }

    @Test
    fun `unknown host is unreachable`() {
        assertEquals(SignInMessages.UNREACHABLE, message(UnknownHostException("Unable to resolve host \"jelly\"")))
    }

    @Test
    fun `no route to host is unreachable`() {
        assertEquals(SignInMessages.UNREACHABLE, message(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `connect exception without a refusal is unreachable`() {
        assertEquals(
            SignInMessages.UNREACHABLE,
            message(ConnectException("failed to connect to /10.0.0.9 (port 8096): ENETUNREACH (Network is unreachable)")),
        )
    }

    @Test
    fun `android ECONNREFUSED is refused`() {
        assertEquals(
            SignInMessages.REFUSED,
            message(
                ConnectException(
                    "failed to connect to /192.168.1.5 (port 8097) from /192.168.1.20 (port 40000) after 10ms: " +
                        "isConnected failed: ECONNREFUSED (Connection refused)",
                ),
            ),
        )
    }

    @Test
    fun `jvm connection refused is refused`() {
        assertEquals(SignInMessages.REFUSED, message(ConnectException("Connection refused")))
    }

    @Test
    fun `okhttp wrapped refusal is refused`() {
        // OkHttp's RealConnection rethrows as "Failed to connect to …" with the
        // ECONNREFUSED exception as the cause.
        val wrapped =
            ConnectException("Failed to connect to /192.168.1.5:8097").apply {
                initCause(ConnectException("isConnected failed: ECONNREFUSED (Connection refused)"))
            }
        assertEquals(SignInMessages.REFUSED, message(wrapped))
    }

    @Test
    fun `ssl exception is certificate rejected`() {
        assertEquals(SignInMessages.CERTIFICATE_REJECTED, message(SSLException("Unrecognized SSL message")))
    }

    @Test
    fun `ssl handshake exception is certificate rejected`() {
        assertEquals(
            SignInMessages.CERTIFICATE_REJECTED,
            message(SSLHandshakeException("Trust anchor for certification path not found.")),
        )
    }

    @Test
    fun `ssl peer unverified is certificate rejected`() {
        assertEquals(
            SignInMessages.CERTIFICATE_REJECTED,
            message(SSLPeerUnverifiedException("Hostname jelly not verified")),
        )
    }

    @Test
    fun `okhttp call timeout is timed out`() {
        assertEquals(SignInMessages.TIMED_OUT, message(InterruptedIOException("timeout")))
    }

    @Test
    fun `call timeout wins over the ssl error of the socket it closed`() {
        val timeout = InterruptedIOException("timeout").apply { initCause(SSLException("Socket closed")) }
        assertEquals(SignInMessages.TIMED_OUT, message(timeout))
    }

    @Test
    fun `socket timeout is timed out`() {
        assertEquals(SignInMessages.TIMED_OUT, message(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun `interrupted io that is not a timeout keeps the old message`() {
        assertNull(message(InterruptedIOException("interrupted")))
    }

    @Test
    fun `invalid credentials is wrong username or password`() {
        assertEquals(SignInMessages.WRONG_CREDENTIALS, message(JellyfinError.InvalidCredentials))
    }

    @Test
    fun `http 401 is wrong username or password`() {
        assertEquals(SignInMessages.WRONG_CREDENTIALS, message(JellyfinError.HttpError(401)))
    }

    @Test
    fun `the client's network error is unwrapped`() {
        assertEquals(
            SignInMessages.UNREACHABLE,
            message(JellyfinError.NetworkError(UnknownHostException("jelly"))),
        )
        assertEquals(
            SignInMessages.TIMED_OUT,
            message(JellyfinError.NetworkError(InterruptedIOException("timeout"))),
        )
    }

    @Test
    fun `other errors keep the existing message`() {
        assertNull(message(JellyfinError.HttpError(500)))
        assertNull(message(JellyfinError.DecodingError))
        assertNull(message(JellyfinError.NetworkError(SocketException("Connection reset"))))
        assertNull(message(IOException("unexpected end of stream")))
        assertNull(message(IllegalStateException("boom")))
    }
}
