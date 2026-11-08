package dev.bitstorm.sashimi.core.seerr

import dev.bitstorm.sashimi.core.session.TokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Collections

/**
 * Answers Seerr (and plex.tv) requests from a table of "METHOD /path" to
 * replies and records every request, so tests assert what would have been
 * sent without any server. Port of sashimi-apple SeerrStubServer. Nothing
 * here reaches the network: unknown routes answer 404.
 */
class SeerrStub : Interceptor {
    data class Reply(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    data class Recorded(
        val method: String,
        val url: HttpUrl,
        val headers: Map<String, String>,
        val body: String?,
    ) {
        val path: String get() = url.encodedPath
        val json: JsonObject? get() = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

        fun query(name: String): String? = url.queryParameter(name)
    }

    private val routes = mutableMapOf<String, MutableList<Reply>>()
    private val recorded: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    @Synchronized
    fun reset() {
        routes.clear()
        recorded.clear()
    }

    /** Queue a reply. Several replies for one route are answered in order and the last repeats. */
    @Synchronized
    fun on(
        method: String,
        path: String,
        status: Int = 200,
        body: String = "{}",
        headers: Map<String, String> = emptyMap(),
    ) {
        routes.getOrPut("$method $path") { mutableListOf() } += Reply(status, body, headers)
    }

    val requests: List<Recorded> get() = synchronized(recorded) { recorded.toList() }

    fun requests(
        method: String,
        path: String,
    ): List<Recorded> = requests.filter { it.method == method && it.path == path }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
        val reply =
            synchronized(this) {
                // Content-Type rides on the body until OkHttp's bridge copies it to a header.
                val headers = request.headers.toMap() + listOfNotNull(request.body?.contentType()?.let { "Content-Type" to it.toString() })
                recorded += Recorded(request.method, request.url, headers, body)
                val queue = routes["${request.method} ${request.url.encodedPath}"]
                when {
                    queue.isNullOrEmpty() -> Reply(404, """{"message":"Not found"}""")
                    queue.size > 1 -> queue.removeAt(0)
                    else -> queue.first()
                }
            }
        val builder =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.status)
                .message("stub")
                .body(reply.body.toResponseBody("application/json".toMediaType()))
        reply.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }
}

class MemoryTokenStore : TokenStore {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun save(
        value: String,
        key: String,
    ): Boolean {
        values[key] = value
        return true
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}

class MemorySeerrSettings : SeerrSettingsStore {
    val strings = mutableMapOf<String, String>()
    val ints = mutableMapOf<String, Int>()

    override fun getString(key: String): String? = strings[key]

    override fun getInt(key: String): Int? = ints[key]

    override fun putString(
        key: String,
        value: String?,
    ) {
        if (value == null) strings.remove(key) else strings[key] = value
    }

    override fun putInt(
        key: String,
        value: Int?,
    ) {
        if (value == null) ints.remove(key) else ints[key] = value
    }
}

object SeerrFixtures {
    const val SERVER = "https://seerr.test"
    const val SESSION_COOKIE = "connect.sid=s%3Atest-session.sig"
    const val SET_COOKIE = "connect.sid=s%3Atest-session.sig; Path=/; Expires=Wed, 01 Dec 2027 00:00:00 GMT; HttpOnly"

    fun user(
        id: Int = 7,
        name: String = "mom",
    ) =
        """{"id":$id,"email":"mom@example.com","displayName":"$name","plexToken":"must-not-be-kept","createdAt":"2026-01-01T00:00:00.000Z"}"""

    fun session(
        stub: SeerrStub,
        cookies: MemoryTokenStore = MemoryTokenStore(),
        settings: MemorySeerrSettings = MemorySeerrSettings(),
    ): SeerrSession =
        SeerrSession(
            settings = settings,
            cookies = cookies,
            pollIntervalMillis = 0,
            clientFactory = { url, cookie -> SeerrClient(url, cookie, stub.http) },
            plexFactory = { PlexPinClient(it, "1.0", stub.http) },
        ).also { it.setServerAddress(SERVER) }

    /** A session signed in as [userJson], with the stub cleared afterwards. */
    suspend fun signedIn(
        stub: SeerrStub,
        userJson: String = user(),
        cookies: MemoryTokenStore = MemoryTokenStore(),
        settings: MemorySeerrSettings = MemorySeerrSettings(),
    ): SeerrSession {
        val session = session(stub, cookies, settings)
        stub.on("POST", "/api/v1/auth/local", body = userJson, headers = mapOf("Set-Cookie" to SET_COOKIE))
        session.signInLocal("test@example.com", "pw")
        stub.reset()
        return session
    }
}
