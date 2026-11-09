package dev.bitstorm.sashimi.core.lists

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Collections

/** A request the stub saw: "METHOD /path?query" plus its body. */
internal data class Seen(
    val call: String,
    val body: String?,
)

/** Answers "METHOD /path" from a table (404 when absent) and records every call. */
internal class StubServer : Interceptor {
    val routes = mutableMapOf<String, Pair<Int, String>>()

    /** Answers used once each, in order, before [routes]. */
    val queued = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()

    /** Paths that fail as if the network were down. */
    val unreachable = mutableSetOf<String>()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = "${request.method} ${request.url.encodedPath}"
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        seen += Seen(key + (request.url.encodedQuery?.let { "?$it" } ?: ""), body)
        if (key in unreachable) throw java.io.IOException("unreachable")
        val (status, json) = queued[key]?.removeFirstOrNull() ?: routes[key] ?: (404 to "")
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    fun calls(prefix: String) = seen.filter { it.call.startsWith(prefix) }
}
