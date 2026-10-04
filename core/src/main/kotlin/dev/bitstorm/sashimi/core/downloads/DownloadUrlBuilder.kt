package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.network.JellyfinClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where a download fetches from, and the token it authenticates with. */
data class DownloadRequestSpec(
    val url: String,
    val accessToken: String,
)

/**
 * Builds the download stream URL for a given quality, ported from the Swift
 * `DownloadURLBuilder`. Pure (server base + ids in, URL out) so the
 * quality→param mapping is unit-testable without a client.
 *
 * - [DownloadQuality.ORIGINAL] → `/Items/{id}/Download` (raw file, no params).
 * - transcoded tiers → `/Videos/{id}/stream.mp4` with a fixed h264/aac/mp4
 *   target and the tier's video bitrate, audio bitrate, frame size and channel
 *   count (see [DownloadQuality.TranscodeTarget]).
 *
 * The access token is NOT in the URL — it rides in an `X-Emby-Token` header on
 * the request (see [DownloadWorker]), matching the Swift `authorizedRequest`.
 */
object DownloadUrlBuilder {
    /**
     * The request for [itemId] against whatever server [client] is bound to:
     * its URL, its token, the shared device id. Null when the client has no
     * server or token.
     */
    fun requestFor(
        client: JellyfinClient,
        itemId: String,
        quality: DownloadQuality,
    ): DownloadRequestSpec? {
        val server = client.currentServerUrl ?: return null
        val token = client.currentAccessToken ?: return null
        val url = downloadUrl(server, itemId, client.currentDeviceId, quality) ?: return null
        return DownloadRequestSpec(url, token)
    }

    fun downloadUrl(
        serverUrl: String,
        itemId: String,
        deviceId: String,
        quality: DownloadQuality,
    ): String? {
        val encode = quality.encode
        return if (encode == null) {
            originalUrl(serverUrl, itemId)
        } else {
            transcodedUrl(serverUrl, itemId, deviceId, encode)
        }
    }

    /**
     * External WebVTT subtitle stream URL for a given subtitle stream index,
     * ported from the Swift `DownloadURLBuilder.subtitleURL` (note the itemId
     * appears twice in the path). The access token rides in an `X-Emby-Token`
     * header on the request, matching the video download.
     */
    fun subtitleUrl(
        serverUrl: String,
        itemId: String,
        subtitleIndex: Int,
    ): String? {
        val base = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("Videos/$itemId/$itemId/Subtitles/$subtitleIndex/Stream.vtt")
            .build()
            .toString()
    }

    private fun originalUrl(
        serverUrl: String,
        itemId: String,
    ): String? {
        val base = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("Items/$itemId/Download")
            .build()
            .toString()
    }

    /**
     * Every encoder input is spelled out with the progressive endpoint's own
     * parameter names (Jellyfin `VideosController.GetVideoStream`):
     * VideoBitRate/AudioBitRate set the encode, MaxWidth/MaxHeight the frame,
     * AudioChannels/MaxAudioChannels the downmix.
     *
     * `MaxStreamingBitrate` is NOT one of them. It belongs to PlaybackInfo
     * negotiation; this endpoint ignores it. Sending only that left the server
     * with no video bitrate, so it either encoded at about 1 kbps (h264_qsv with
     * `-b:v 0`) or stream-copied the full-size video, whatever tier was picked.
     */
    private fun transcodedUrl(
        serverUrl: String,
        itemId: String,
        deviceId: String,
        encode: DownloadQuality.TranscodeTarget,
    ): String? {
        val base = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("Videos/$itemId/stream.mp4")
            .addQueryParameter("MediaSourceId", itemId)
            .addQueryParameter("VideoCodec", "h264")
            .addQueryParameter("AudioCodec", "aac")
            .addQueryParameter("Container", "mp4")
            .addQueryParameter("VideoBitRate", encode.videoBitrate.toString())
            .addQueryParameter("AudioBitRate", encode.audioBitrate.toString())
            .addQueryParameter("MaxWidth", encode.maxWidth.toString())
            .addQueryParameter("MaxHeight", encode.maxHeight.toString())
            .addQueryParameter("AudioChannels", encode.audioChannels.toString())
            .addQueryParameter("MaxAudioChannels", encode.audioChannels.toString())
            .addQueryParameter("DeviceId", deviceId)
            .build()
            .toString()
    }
}
