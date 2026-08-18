package dev.bitstorm.sashimi.core.network

import dev.bitstorm.sashimi.core.model.BaseItemDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `GET /Items/{itemId}/ThemeMedia` response. The companion Theme Songs plugin
 * drops a `theme.mp3` into each series folder and Jellyfin surfaces it here;
 * only [themeSongsResult] is of interest (theme *videos* and soundtracks are
 * separate arrays this client ignores).
 *
 * Everything is nullable/defaulted: about 41% of a typical library has no theme
 * at all, and an absent result is the normal case, not an error.
 */
@Serializable
data class ThemeMediaResponse(
    @SerialName("ThemeSongsResult") val themeSongsResult: ThemeMediaResult? = null,
)

@Serializable
data class ThemeMediaResult(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("OwnerId") val ownerId: String? = null,
)

/**
 * Builds the theme-song audio stream URL. Pure (server base + ids in, URL out)
 * so it is unit-testable without a configured client, following
 * [dev.bitstorm.sashimi.core.downloads.DownloadUrlBuilder].
 *
 * Two deliberate choices, both verified against Jellyfin 10.11.11:
 *
 *  - `/Audio/{id}/stream.mp3` and **not** `/Audio/{id}/universal`. The universal
 *    endpoint returns **400** unless a full device profile is supplied; the
 *    static one returns `200 audio/mpeg` for a real theme.
 *  - The `api_key` rides in the URL. ExoPlayer fetches the stream itself and
 *    has no supported way to attach the MediaBrowser auth header, exactly as
 *    the video path already does in [JellyfinClient.getPlaybackURL].
 */
object ThemeMediaUrlBuilder {
    fun audioStreamUrl(
        serverUrl: String,
        themeItemId: String,
        apiKey: String,
    ): String? {
        val base = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("Audio/$themeItemId/stream.mp3")
            .addQueryParameter("api_key", apiKey)
            .build()
            .toString()
    }
}
