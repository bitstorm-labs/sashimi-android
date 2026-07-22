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

    @Test
    fun `transcoded tier hits stream-mp4 with bitrate and h264 aac mp4 params`() {
        val url = DownloadUrlBuilder.downloadUrl(server, "abc", device, DownloadQuality.MEDIUM)!!.toHttpUrl()
        assertTrue(url.encodedPath.endsWith("/Videos/abc/stream.mp4"))
        assertEquals("abc", url.queryParameter("MediaSourceId"))
        assertEquals("8000000", url.queryParameter("MaxStreamingBitrate"))
        assertEquals("h264", url.queryParameter("VideoCodec"))
        assertEquals("aac", url.queryParameter("AudioCodec"))
        assertEquals("mp4", url.queryParameter("Container"))
        assertEquals(device, url.queryParameter("DeviceId"))
    }

    @Test
    fun `each tier maps to its exact bitrate`() {
        fun bitrateOf(q: DownloadQuality) =
            DownloadUrlBuilder.downloadUrl(server, "x", device, q)?.toHttpUrl()?.queryParameter("MaxStreamingBitrate")
        assertEquals("20000000", bitrateOf(DownloadQuality.HIGH))
        assertEquals("8000000", bitrateOf(DownloadQuality.MEDIUM))
        assertEquals("4000000", bitrateOf(DownloadQuality.LOW))
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
