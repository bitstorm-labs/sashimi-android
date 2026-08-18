package dev.bitstorm.sashimi.core.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMediaTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val server = "https://jelly.example.com"
    private val token = "test-token"

    // MARK: - URL builder

    @Test
    fun `audio stream url hits the static mp3 endpoint with the api key`() {
        val url = ThemeMediaUrlBuilder.audioStreamUrl(server, "theme-1", token)!!.toHttpUrl()
        assertEquals("/Audio/theme-1/stream.mp3", url.encodedPath)
        assertEquals(token, url.queryParameter("api_key"))
    }

    @Test
    fun `audio stream url never uses the universal endpoint`() {
        // /Audio/{id}/universal returns 400 without a full device profile.
        val url = ThemeMediaUrlBuilder.audioStreamUrl(server, "theme-1", token)!!
        assertTrue("universal" !in url)
    }

    @Test
    fun `trailing slash on the server is normalised`() {
        assertEquals(
            ThemeMediaUrlBuilder.audioStreamUrl(server, "theme-1", token),
            ThemeMediaUrlBuilder.audioStreamUrl("$server/", "theme-1", token),
        )
    }

    @Test
    fun `a sub-path server base is preserved`() {
        val url = ThemeMediaUrlBuilder.audioStreamUrl("$server/jellyfin", "theme-1", token)!!.toHttpUrl()
        assertEquals("/jellyfin/Audio/theme-1/stream.mp3", url.encodedPath)
    }

    @Test
    fun `a malformed server yields null rather than a broken url`() {
        assertNull(ThemeMediaUrlBuilder.audioStreamUrl("not a url", "theme-1", token))
    }

    // MARK: - response decoding

    @Test
    fun `the theme song item id is read off ThemeSongsResult`() {
        val body =
            """
            {
              "ThemeVideosResult": { "Items": [], "TotalRecordCount": 0, "OwnerId": "series-1" },
              "ThemeSongsResult": {
                "Items": [
                  { "Id": "theme-1", "Name": "Theme Song", "Type": "Audio", "RunTimeTicks": 600000000 }
                ],
                "TotalRecordCount": 1,
                "OwnerId": "series-1"
              },
              "SoundtrackSongsResult": { "Items": [], "TotalRecordCount": 0, "OwnerId": "series-1" }
            }
            """.trimIndent()
        val decoded = json.decodeFromString<ThemeMediaResponse>(body)
        assertEquals("theme-1", decoded.themeSongsResult?.items?.firstOrNull()?.id)
        assertEquals(1, decoded.themeSongsResult?.totalRecordCount)
    }

    @Test
    fun `a series with no theme decodes to an empty list, not an error`() {
        val body =
            """
            { "ThemeSongsResult": { "Items": [], "TotalRecordCount": 0, "OwnerId": "series-1" } }
            """.trimIndent()
        val decoded = json.decodeFromString<ThemeMediaResponse>(body)
        assertEquals(emptyList<Any>(), decoded.themeSongsResult?.items)
        assertNull(decoded.themeSongsResult?.items?.firstOrNull()?.id)
    }

    @Test
    fun `a response with no ThemeSongsResult at all decodes to null`() {
        val decoded = json.decodeFromString<ThemeMediaResponse>("""{ "ThemeVideosResult": { "Items": [] } }""")
        assertNull(decoded.themeSongsResult)
    }

    @Test
    fun `theme videos are ignored -- only the song result is read`() {
        val body =
            """
            {
              "ThemeVideosResult": { "Items": [ { "Id": "video-1", "Name": "Intro" } ], "TotalRecordCount": 1 },
              "ThemeSongsResult": { "Items": [], "TotalRecordCount": 0 }
            }
            """.trimIndent()
        val decoded = json.decodeFromString<ThemeMediaResponse>(body)
        assertNull(decoded.themeSongsResult?.items?.firstOrNull()?.id)
    }
}
