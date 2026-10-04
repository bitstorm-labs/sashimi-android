package dev.bitstorm.sashimi.core.downloads

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUrlBuilderTest {
    private val server = "https://jelly.example.com"
    private val device = "device-123"

    @Test
    fun `original downloads the raw file with no query params`() {
        val url = DownloadUrlBuilder.downloadUrl(server, "abc", device, DownloadQuality.ORIGINAL)!!
        assertEquals("https://jelly.example.com/Items/abc/Download", url)
    }

    private fun query(quality: DownloadQuality): Map<String, String?> {
        val url = DownloadUrlBuilder.downloadUrl(server, "abc", device, quality)!!.toHttpUrl()
        return url.queryParameterNames.associateWith { url.queryParameter(it) }
    }

    @Test
    fun `transcoded tier hits stream-mp4 with an h264 aac mp4 target`() {
        val url = DownloadUrlBuilder.downloadUrl(server, "abc", device, DownloadQuality.MEDIUM)!!.toHttpUrl()
        assertTrue(url.encodedPath.endsWith("/Videos/abc/stream.mp4"))
        assertEquals("abc", url.queryParameter("MediaSourceId"))
        assertEquals("h264", url.queryParameter("VideoCodec"))
        assertEquals("aac", url.queryParameter("AudioCodec"))
        assertEquals("mp4", url.queryParameter("Container"))
        assertEquals(device, url.queryParameter("DeviceId"))
    }

    // The three tier tests pin the parameters Jellyfin's progressive endpoint
    // (VideosController.GetVideoStream) actually reads. Without VideoBitRate the
    // server encodes at about 1 kbps or stream-copies the full-size video.

    @Test
    fun `high asks for a 1080p encode at 20 Mbps total with 6 channel audio`() {
        val q = query(DownloadQuality.HIGH)
        assertEquals("19616000", q["VideoBitRate"])
        assertEquals("384000", q["AudioBitRate"])
        assertEquals("1920", q["MaxWidth"])
        assertEquals("1080", q["MaxHeight"])
        assertEquals("6", q["AudioChannels"])
        assertEquals("6", q["MaxAudioChannels"])
    }

    @Test
    fun `medium asks for a 720p encode at 8 Mbps total with stereo audio`() {
        val q = query(DownloadQuality.MEDIUM)
        assertEquals("7808000", q["VideoBitRate"])
        assertEquals("192000", q["AudioBitRate"])
        assertEquals("1280", q["MaxWidth"])
        assertEquals("720", q["MaxHeight"])
        assertEquals("2", q["AudioChannels"])
        assertEquals("2", q["MaxAudioChannels"])
    }

    @Test
    fun `low asks for a 480p encode at 4 Mbps total with stereo audio`() {
        val q = query(DownloadQuality.LOW)
        assertEquals("3872000", q["VideoBitRate"])
        assertEquals("128000", q["AudioBitRate"])
        assertEquals("854", q["MaxWidth"])
        assertEquals("480", q["MaxHeight"])
        assertEquals("2", q["AudioChannels"])
        assertEquals("2", q["MaxAudioChannels"])
    }

    @Test
    fun `no tier sends MaxStreamingBitrate, which the progressive endpoint ignores`() {
        DownloadQuality.entries.filter { it != DownloadQuality.ORIGINAL }.forEach {
            assertNull("${it.wireName} must not rely on MaxStreamingBitrate", query(it)["MaxStreamingBitrate"])
        }
    }

    @Test
    fun `video plus audio bitrate equals the bitrate the tier is labelled with`() {
        mapOf(DownloadQuality.HIGH to 20, DownloadQuality.MEDIUM to 8, DownloadQuality.LOW to 4).forEach { (quality, mbps) ->
            val q = query(quality)
            assertEquals(mbps * 1_000_000, q["VideoBitRate"]!!.toInt() + q["AudioBitRate"]!!.toInt())
            assertTrue("label must state the total", quality.subtitle.contains("$mbps Mbps"))
        }
    }

    @Test
    fun `token never appears in the url`() {
        val url = DownloadUrlBuilder.downloadUrl(server, "abc", device, DownloadQuality.HIGH)!!
        assertTrue("api_key" !in url && "Token" !in url)
    }

    @Test
    fun `malformed server yields null`() {
        assertNull(DownloadUrlBuilder.downloadUrl("not a url", "abc", device, DownloadQuality.HIGH))
    }

    @Test
    fun `trailing slash on server is normalised`() {
        val url = DownloadUrlBuilder.downloadUrl("$server/", "abc", device, DownloadQuality.ORIGINAL)!!
        assertEquals("https://jelly.example.com/Items/abc/Download", url)
    }
}
