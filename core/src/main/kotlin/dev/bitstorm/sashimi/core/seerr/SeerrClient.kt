package dev.bitstorm.sashimi.core.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What went wrong talking to Seerr or plex.tv; [message] is what the screen shows. */
sealed class SeerrError(
    override val message: String,
) : Exception(message) {
    data object InvalidServerUrl : SeerrError("That doesn't look like a server address.")

    /** 401/403 on a signed-in call. Usually an expired session; the session checks `/auth/me` first. */
    data object Unauthorized : SeerrError("Seerr didn't allow that.")

    /** A sign-in the server turned down. */
    data object InvalidCredentials : SeerrError("That email and password didn't work.")

    /** The server signed us in but sent no session cookie to keep. */
    data object NoSession : SeerrError("The server signed in but didn't start a session. Check the server address.")

    data object NothingToRequest : SeerrError("Pick at least one season.")

    data class Server(
        val status: Int,
        val serverMessage: String?,
    ) : SeerrError(serverMessage?.takeIf { it.isNotEmpty() } ?: "The request server answered with an error ($status).")

    data object Decoding : SeerrError("The request server sent something unexpected.")

    data class Network(
        val text: String,
    ) : SeerrError(text)
}

/** A successful sign-in: who, and the session cookie to send from now on. */
data class SeerrSignIn(
    val user: SeerrUser,
    val cookie: String,
)

/**
 * Talks to one Seerr server as one signed-in person, with the `connect.sid`
 * session cookie Seerr hands out on sign-in. The cookie is managed here, not
 * by an OkHttp CookieJar, so it lives only in the encrypted store (see
 * [SeerrSession]) and never reaches Jellyfin requests. Nothing here logs a
 * cookie, token or password. Port of sashimi-apple SeerrClient.
 */
class SeerrClient(
    val baseUrl: String,
    cookie: String?,
    private val http: OkHttpClient = defaultHttp,
) {
    @Volatile
    private var cookie: String? = cookie

    // MARK: Auth

    suspend fun publicSettings(): SeerrPublicSettings = decode(send("GET", "settings/public").body)

    suspend fun signInWithPlex(authToken: String): SeerrSignIn = signIn("auth/plex", mapOf("authToken" to authToken))

    suspend fun signInLocal(
        email: String,
        password: String,
    ): SeerrSignIn = signIn("auth/local", mapOf("email" to email, "password" to password))

    suspend fun currentUser(): SeerrUser = decode(send("GET", "auth/me").body)

    /** Best effort: the local sign-out happens whatever the server says. */
    suspend fun logout() {
        try {
            send("POST", "auth/logout", body = "{}")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // MARK: Browse

    suspend fun search(
        query: String,
        page: Int = 1,
    ): SeerrSearchPage = parse { SeerrSearchPage.parse(send("GET", "search", "query=${encodeQuery(query)}&page=$page").body) }

    suspend fun details(
        kind: SeerrMediaKind,
        tmdbId: Int,
    ): SeerrTitleDetails = parse { SeerrTitleDetails.parse(send("GET", "${kind.wire}/$tmdbId").body) }

    // MARK: Requests

    suspend fun requestMovie(tmdbId: Int): SeerrRequest = postRequest(SeerrRequestBody(SeerrMediaKind.MOVIE.wire, tmdbId))

    /** Never season 0: specials are off on the server and never offered. */
    suspend fun requestShow(
        tmdbId: Int,
        seasons: List<Int>,
    ): SeerrRequest {
        val numbers = seasons.filter { it > 0 }.distinct().sorted()
        if (numbers.isEmpty()) throw SeerrError.NothingToRequest
        return postRequest(SeerrRequestBody(SeerrMediaKind.TV.wire, tmdbId, numbers))
    }

    /** The person's own requests, newest first. */
    suspend fun requests(
        requestedBy: Int,
        take: Int = 50,
    ): List<SeerrRequest> =
        decode<SeerrRequestPage>(send("GET", "request", "take=$take&skip=0&sort=added&requestedBy=$requestedBy").body).results

    /** The requester may cancel while pending; MANAGE_REQUESTS can delete any request. */
    suspend fun cancelRequest(id: Int) {
        send("DELETE", "request/$id")
    }

    /**
     * One page of `GET /request`, newest first. Without [requestedBy] the
     * server lists everyone's for MANAGE_REQUESTS / REQUEST_VIEW and only the
     * caller's for anyone else.
     */
    suspend fun requestPage(
        filter: SeerrRequestFilter,
        take: Int,
        skip: Int,
        requestedBy: Int?,
    ): SeerrRequestPage {
        var raw = "take=$take&skip=$skip&filter=${filter.wire}&sort=added&sortDirection=desc"
        if (requestedBy != null) raw += "&requestedBy=$requestedBy"
        return decode(send("GET", "request", raw).body)
    }

    /** MANAGE_REQUESTS only, and only while pending (409 after). */
    suspend fun approveRequest(id: Int): SeerrRequest = decode(send("POST", "request/$id/approve", body = "{}").body)

    suspend fun declineRequest(id: Int): SeerrRequest = decode(send("POST", "request/$id/decline", body = "{}").body)

    suspend fun requestCount(): SeerrRequestCount = decode(send("GET", "request/count").body)

    suspend fun quota(userId: Int): SeerrQuota = decode(send("GET", "user/$userId/quota").body)

    // MARK: Discover

    /** One page of a Discover row. Recent Requests is `requestPage`, not a discover route. */
    suspend fun discover(
        slider: SeerrDiscoverSlider,
        page: Int = 1,
    ): SeerrSearchPage {
        val path = slider.path ?: throw SeerrError.InvalidServerUrl
        return parse { SeerrSearchPage.parse(send("GET", path, "page=$page").body) }
    }

    // MARK: Plumbing

    private class Reply(
        val body: String,
        val setCookies: List<String>,
    )

    private suspend fun postRequest(body: SeerrRequestBody): SeerrRequest =
        decode(send("POST", "request", body = SeerrJson.json.encodeToString(body)).body)

    private suspend fun signIn(
        path: String,
        body: Map<String, String>,
    ): SeerrSignIn {
        val reply = send("POST", path, body = SeerrJson.json.encodeToString(body), isSignIn = true)
        val cookie = sessionCookie(reply.setCookies) ?: throw SeerrError.NoSession
        val user = decode<SeerrUser>(reply.body)
        this.cookie = cookie
        return SeerrSignIn(user, cookie)
    }

    private suspend fun send(
        method: String,
        path: String,
        rawQuery: String? = null,
        body: String? = null,
        isSignIn: Boolean = false,
    ): Reply {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: throw SeerrError.InvalidServerUrl
        val url =
            base
                .newBuilder()
                .addPathSegments("api/v1/$path")
                .encodedQuery(rawQuery)
                .build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        val cookie = cookie
        if (cookie != null && !isSignIn) builder.header("Cookie", cookie)
        builder.method(method, body?.toRequestBody(JSON))
        val it =
            try {
                http.newCall(builder.build()).awaitReply()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                throw SeerrError.Network("Couldn't reach the request server.")
            }
        run {
            val text = it.body
            return when (it.code) {
                in 200..299 -> Reply(text, it.setCookies)
                401, 403 -> throw if (isSignIn) SeerrError.InvalidCredentials else SeerrError.Unauthorized
                else -> {
                    val error = runCatching { SeerrJson.json.decodeFromString<SeerrErrorBody>(text) }.getOrNull()
                    throw SeerrError.Server(it.code, error?.message ?: error?.error)
                }
            }
        }
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            SeerrJson.json.decodeFromString(text)
        } catch (_: Exception) {
            throw SeerrError.Decoding
        }

    private inline fun <T> parse(block: () -> T): T =
        try {
            block()
        } catch (e: SeerrError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw SeerrError.Decoding
        }

    companion object {
        const val SESSION_COOKIE_NAME = "connect.sid"
        private val JSON = "application/json".toMediaType()

        /** Ephemeral: no cookie jar, no cache, short timeouts. */
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Seerr rejects loosely encoded queries (a literal space, `'`, `(`...), so
         * everything but RFC 3986 unreserved characters is percent-encoded.
         */
        fun encodeQuery(value: String): String {
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~") {
                    out.append(ch)
                } else {
                    out.append('%').append("%02X".format(c))
                }
            }
            return out.toString()
        }

        /** `connect.sid=<value>` from the response's Set-Cookie headers, attributes dropped. */
        fun sessionCookie(setCookies: List<String>): String? =
            setCookies
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.substringBefore('=') == SESSION_COOKIE_NAME && it.substringAfter('=', "").isNotEmpty() }
    }
}

/**
 * Plex's PIN sign-in: the phone opens Plex's sign-in page in a Custom Tab
 * with a strong PIN; the PIN is polled until it carries the person's Plex
 * token, which is handed to Seerr once and never stored.
 */
class PlexPinClient(
    private val clientIdentifier: String,
    private val version: String,
    private val http: OkHttpClient = SeerrClient.defaultHttp,
    private val pinsUrl: String = PINS_URL,
) {
    suspend fun createPin(strong: Boolean): PlexPin = send("$pinsUrl?strong=$strong", "POST")

    suspend fun checkPin(id: Int): PlexPin = send("$pinsUrl/$id", "GET")

    private suspend fun send(
        url: String,
        method: String,
    ): PlexPin {
        val request =
            Request
                .Builder()
                .url(url)
                .method(method, if (method == "POST") ByteArray(0).toRequestBody(null) else null)
                .header("Accept", "application/json")
                .header("X-Plex-Product", PRODUCT)
                .header("X-Plex-Client-Identifier", clientIdentifier)
                .header("X-Plex-Version", version)
                .header("X-Plex-Platform", "Android")
                .header("X-Plex-Device-Name", "Sashimi (Android)")
                .build()
        val it =
            try {
                http.newCall(request).awaitReply()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                throw SeerrError.Network("Couldn't reach plex.tv.")
            }
        run {
            if (it.code !in 200..299) throw SeerrError.Server(it.code, null)
            val text = it.body
            return try {
                SeerrJson.json.decodeFromString(text)
            } catch (_: Exception) {
                throw SeerrError.Decoding
            }
        }
    }

    companion object {
        const val PRODUCT = "Sashimi"
        const val PINS_URL = "https://plex.tv/api/v2/pins"

        /**
         * Plex's own sign-in page for a strong PIN, the way Seerr's website opens it.
         * No forwardUrl: Plex accepts only its own app schemes or http(s) there, and with
         * a custom scheme it jumps to watch.plex.tv without ever linking the PIN (#232).
         */
        fun webSignInUrl(
            clientIdentifier: String,
            code: String,
        ): String {
            val params =
                listOf(
                    "clientID" to clientIdentifier,
                    "code" to code,
                    "context[device][product]" to PRODUCT,
                )
            val query = params.joinToString("&") { (k, v) -> "${SeerrClient.encodeQuery(k)}=${SeerrClient.encodeQuery(v)}" }
            return "https://app.plex.tv/auth/#!?$query"
        }
    }
}

/** A finished HTTP exchange, body already read. */
internal class HttpReply(
    val code: Int,
    val body: String,
    val setCookies: List<String>,
)

/**
 * OkHttp's async call as a cancellable suspend, with the body read on the IO
 * dispatcher too. Reading it on the caller's (main) thread threw
 * NetworkOnMainThreadException for any body not already buffered, which
 * surfaced as "sent something unexpected" on every Discover row.
 */
internal suspend fun Call.awaitReply(): HttpReply =
    withContext(Dispatchers.IO) {
        val response =
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { cancel() } }
                enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (!cont.isCancelled) cont.resumeWithException(e)
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            cont.resume(response)
                        }
                    },
                )
            }
        response.use { HttpReply(it.code, it.body.string(), it.headers("Set-Cookie")) }
    }
