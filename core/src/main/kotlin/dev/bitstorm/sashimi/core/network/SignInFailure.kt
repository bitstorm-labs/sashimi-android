package dev.bitstorm.sashimi.core.network

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Budget for each sign-in network step (address probe, authenticate). Someone
 * is watching the screen and has probably just mistyped an address, so this is
 * far shorter than the browsing client's 120s call timeout (#59). Shared with
 * the Apple and Roku clients.
 */
const val SIGN_IN_TIMEOUT_SECONDS = 12L

/**
 * The user-facing reason a sign-in step failed, or null when the failure is not
 * one of the cases below (the caller then keeps its existing message).
 *
 * Unreachable host, connection refused, TLS rejected, reachable-but-silent and
 * bad credentials are five different problems with five different fixes, so
 * each gets its own message. The copy matches sashimi-apple and sashimi-roku.
 *
 * Walks the cause chain outermost-first, because OkHttp wraps the interesting
 * exception: a refused connect arrives as `ConnectException("Failed to connect
 * to …")` caused by the one carrying ECONNREFUSED, and a call timeout arrives as
 * `InterruptedIOException("timeout")` caused by whatever the cancelled socket
 * threw (which can itself be an SSLException, so the timeout must win).
 *
 * Pure: no Android dependencies, so a TV client can reuse it.
 */
fun signInFailureMessage(error: Throwable): String? {
    if (error is JellyfinError.InvalidCredentials) return SignInMessages.WRONG_CREDENTIALS
    if (error is JellyfinError.HttpError && error.statusCode == 401) return SignInMessages.WRONG_CREDENTIALS

    for (e in causeChain(error)) {
        when {
            e is SocketTimeoutException -> return SignInMessages.TIMED_OUT
            e is InterruptedIOException && e.message == "timeout" -> return SignInMessages.TIMED_OUT
            e is SSLException -> return SignInMessages.CERTIFICATE_REJECTED
            e is UnknownHostException -> return SignInMessages.UNREACHABLE
            e is NoRouteToHostException -> return SignInMessages.UNREACHABLE
            e is ConnectException ->
                return if (causeChain(e).any { it is ConnectException && it.isRefused() }) {
                    SignInMessages.REFUSED
                } else {
                    SignInMessages.UNREACHABLE
                }
        }
    }
    return null
}

/** The shared sign-in copy. Kept verbatim across all three clients. */
object SignInMessages {
    const val UNREACHABLE =
        "Can't reach a server at that address. Check the address and that you're on the same network."
    const val REFUSED = "The server refused the connection. Check the port, and that Jellyfin is running."
    const val CERTIFICATE_REJECTED =
        "The server's security certificate was rejected. " +
            "Check whether the address should start with http:// or https://."
    const val TIMED_OUT =
        "The server didn't answer within $SIGN_IN_TIMEOUT_SECONDS seconds. It may be down or busy. Try again."
    const val WRONG_CREDENTIALS = "Wrong username or password."
}

// Android: "... isConnected failed: ECONNREFUSED (Connection refused)"; JVM: "Connection refused".
private fun ConnectException.isRefused(): Boolean {
    val text = message ?: return false
    return text.contains("ECONNREFUSED") || text.contains("Connection refused", ignoreCase = true)
}

/** [error] and its causes, outermost first. [JellyfinError.NetworkError] keeps its cause in `underlying`. */
private fun causeChain(error: Throwable): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && current !in chain) {
        chain += current
        current = (current as? JellyfinError.NetworkError)?.underlying ?: current.cause
    }
    return chain
}
