package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Answers requests from a path -> (status, JSON) table and records what was asked. */
private class StubServer : Interceptor {
    val routes = mutableMapOf<String, Pair<Int, String>>()
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        requested += path
        val (status, body) = routes[path] ?: (404 to "")
        return Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

private fun items(vararg entries: Triple<String, String, String?>): String =
    entries.joinToString(prefix = """{"Items":[""", postfix = """],"TotalRecordCount":${entries.size}}""") { (id, series, played) ->
        val date = played?.let { """"LastPlayedDate":"$it",""" } ?: ""
        """{"Id":"$id","Name":"$id","Type":"Episode","SeriesId":"$series","UserData":{$date"PlaybackPositionTicks":0,"Played":false}}"""
    }

class ContinueWatchingSourceTest {
    private val user = "u1"
    private val stub = StubServer()

    private fun client() =
        JellyfinClient(
            deviceId = "test",
            httpClient = OkHttpClient.Builder().addInterceptor(stub).build(),
        ).also { it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = user) }

    @Test
    fun `the plugin's row is used as-is and Resume and Next Up are never fetched`() =
        runTest {
            stub.routes["/ContinueWatching"] =
                200 to
                items(
                    Triple("the100-s5e1", "the100", "2026-09-22T02:11:50Z"),
                    Triple("gcn-vo2", "gcn", "2026-10-12T01:12:39Z"),
                )

            val row = ContinueWatchingSource.load(client())

            assertEquals(listOf("the100-s5e1", "gcn-vo2"), row.map { it.id })
            assertFalse("/Users/$user/Items/Resume" in stub.requested)
            assertFalse("/Shows/NextUp" in stub.requested)
        }

    @Test
    fun `without the plugin the row is merged from Resume and Next Up`() =
        runTest {
            stub.routes["/Users/$user/Items/Resume"] = 200 to items(Triple("gcn-vo2", "gcn", "2026-10-12T01:12:39Z"))
            stub.routes["/Shows/NextUp"] = 200 to items(Triple("older-e2", "older", "2026-10-01T00:00:00Z"))
            stub.routes["/Users/$user/Items"] = 200 to items()

            val row = ContinueWatchingSource.load(client())

            assertTrue("the plugin is asked first", "/ContinueWatching" in stub.requested)
            assertEquals(listOf("gcn-vo2", "older-e2"), row.map { it.id })
        }
}
