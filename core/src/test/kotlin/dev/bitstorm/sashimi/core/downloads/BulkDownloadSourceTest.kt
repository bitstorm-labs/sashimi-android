package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkDownloadSourceTest {
    private val requested = mutableListOf<String>()

    // The server's current state: e1 was watched elsewhere after the page loaded.
    private val season1 =
        """{"Items":[
        {"Id":"e1","Name":"e1","Type":"Episode","SeriesId":"show","SeasonId":"s1","ParentIndexNumber":1,"UserData":{"Played":true}},
        {"Id":"e2","Name":"e2","Type":"Episode","SeriesId":"show","SeasonId":"s1","ParentIndexNumber":1,"UserData":{"Played":false}}
        ],"TotalRecordCount":2}"""

    private val client =
        JellyfinClient(
            deviceId = "t",
            httpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            val url = chain.request().url
                            requested += "${url.encodedPath}?SeasonId=${url.queryParameter("SeasonId")}"
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("ok")
                                .body(season1.toResponseBody("application/json".toMediaType()))
                                .build()
                        },
                    ).build(),
        ).also { it.configure("http://stub.local", "token", "u1") }

    @Test
    fun `a season action chooses from the server's current watched state`() =
        runTest {
            val fresh = BulkDownloadSource.episodes(client, BulkDownloadAction.SEASON_UNWATCHED, "show", "s1")
            val picked = BulkDownloadPlanner.select(BulkDownloadAction.SEASON_UNWATCHED, fresh, emptyList(), "a")

            assertEquals(listOf("/Shows/show/Episodes?SeasonId=s1"), requested)
            assertEquals(listOf("e2"), picked.map { it.id })
        }

    @Test
    fun `a series action fetches every season`() =
        runTest {
            BulkDownloadSource.episodes(client, BulkDownloadAction.SERIES_UNWATCHED, "show", "s1")
            assertEquals(listOf("/Shows/show/Episodes?SeasonId=null"), requested)
        }
}
