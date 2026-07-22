package dev.bitstorm.sashimi.core.downloads

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds the download stream URL for a given quality, ported from the Swift
 * `DownloadURLBuilder`. Pure (server base + ids in, URL out) so the
 * quality→param mapping is unit-testable without a client.
 *
 * - [DownloadQuality.ORIGINAL] → `/Items/{id}/Download` (raw file, no params).
 * - transcoded tiers → `/Videos/{id}/stream.mp4` with the tier's bitrate ceiling
 *   and a fixed h264/aac/mp4 target (exactly the Swift query set; there is no
 *   `Static`, `VideoBitrate`, or resolution param).
 *
 * The access token is NOT in the URL — it rides in an `X-Emby-Token` header on
 * the request (see [DownloadWorker]), matching the Swift `authorizedRequest`.
 */
object DownloadUrlBuilder {
    fun downloadUrl(
        serverUrl: String,
        itemId: String,
        deviceId: String,
        quality: DownloadQuality,
    ): String? {
        val bitrate = quality.maxBitrate
        return if (bitrate == null) {
            originalUrl(serverUrl, itemId)
        } else {
            transcodedUrl(serverUrl, itemId, deviceId, bitrate)
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

    private fun transcodedUrl(
        serverUrl: String,
        itemId: String,
        deviceId: String,
        maxBitrate: Int,
    ): String? {
        val base = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("Videos/$itemId/stream.mp4")
            .addQueryParameter("MediaSourceId", itemId)
            .addQueryParameter("MaxStreamingBitrate", maxBitrate.toString())
            .addQueryParameter("VideoCodec", "h264")
            .addQueryParameter("AudioCodec", "aac")
            .addQueryParameter("Container", "mp4")
            .addQueryParameter("DeviceId", deviceId)
            .build()
            .toString()
    }
}
