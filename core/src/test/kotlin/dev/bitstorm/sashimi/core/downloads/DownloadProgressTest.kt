package dev.bitstorm.sashimi.core.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    private val oneHourTicks = 3600L * 10_000_000

    @Test
    fun `estimate is tier video plus audio times runtime`() {
        // Medium: 7.808 Mbps video + 192 kbps audio = 8 Mbps for an hour = 3.6 GB.
        assertEquals(3_600_000_000L, DownloadProgress.estimatedTotalBytes(DownloadQuality.MEDIUM, oneHourTicks))
    }

    @Test
    fun `estimate uses the source video bitrate when it is under the tier`() {
        // 3 Mbps source + 192 kbps audio at Medium.
        assertEquals(
            1_436_400_000L,
            DownloadProgress.estimatedTotalBytes(DownloadQuality.MEDIUM, oneHourTicks, sourceVideoBitrate = 3_000_000),
        )
        // A source above the tier changes nothing.
        assertEquals(
            3_600_000_000L,
            DownloadProgress.estimatedTotalBytes(DownloadQuality.MEDIUM, oneHourTicks, sourceVideoBitrate = 40_000_000),
        )
    }

    @Test
    fun `no estimate for Original or without a runtime`() {
        assertNull(DownloadProgress.estimatedTotalBytes(DownloadQuality.ORIGINAL, oneHourTicks))
        assertNull(DownloadProgress.estimatedTotalBytes(DownloadQuality.LOW, null))
    }

    @Test
    fun `a real Content-Length wins and is not an estimate`() {
        val s = DownloadProgress.snapshot(receivedBytes = 500, reportedTotal = 1_000, estimatedTotal = 9_999)!!
        assertEquals(0.5, s.fraction, 1e-9)
        assertEquals(1_000, s.totalBytes)
        assertEquals(false, s.isEstimate)
    }

    @Test
    fun `an estimate never reads 100 percent and grows once passed`() {
        val half = DownloadProgress.snapshot(210, -1, 420)!!
        assertEquals(0.5, half.fraction, 1e-9)
        assertTrue(half.isEstimate)
        val at = DownloadProgress.snapshot(420, -1, 420)!!
        assertTrue("at the estimate: ${at.fraction}", at.fraction in 0.98..0.99)
        val past = DownloadProgress.snapshot(1_000, -1, 420)!!
        assertTrue("past the estimate: ${past.fraction}", past.fraction in 0.98..0.99)
        assertTrue("estimate grows past received: ${past.totalBytes}", past.totalBytes > 1_000)
    }

    @Test
    fun `no total at all is indeterminate`() {
        assertNull(DownloadProgress.snapshot(100, -1, null))
    }

    @Test
    fun `the row label`() {
        val s = DownloadProgress.snapshot(182_000_000, -1, 420_000_000)
        assertEquals(
            "43% · 182 MB of ~420 MB · 3.1 MB/s · about 1 min left",
            DownloadProgress.label(182_000_000, s, 3_100_000),
        )
        assertEquals("182 MB · 3.1 MB/s", DownloadProgress.label(182_000_000, null, 3_100_000))
        assertEquals(
            "50% · 500 MB of 1.0 GB",
            DownloadProgress.label(500_000_000, DownloadProgress.snapshot(500_000_000, 1_000_000_000, null), null),
        )
    }

    @Test
    fun `time left wording`() {
        assertEquals("less than a minute left", DownloadProgress.timeLeft(30))
        assertEquals("about 1 min left", DownloadProgress.timeLeft(77))
        assertEquals("about 12 min left", DownloadProgress.timeLeft(12 * 60 + 10))
        assertEquals("about 1 h 5 min left", DownloadProgress.timeLeft(3600 + 5 * 60))
    }

    @Test
    fun `transfer rate is smoothed`() {
        val rate = TransferRate(smoothing = 0.5)
        assertNull(rate.sample(0, 0))
        assertEquals(1_000_000L, rate.sample(1_000_000, 1_000))
        // Next second at 3 MB/s: halfway between.
        assertEquals(2_000_000L, rate.sample(4_000_000, 2_000))
    }

    private fun row(
        status: DownloadStatus,
        quality: DownloadQuality = DownloadQuality.MEDIUM,
        progress: Double = 0.0,
        downloaded: Long = 0,
        total: Long = 0,
    ) = DownloadedItemEntity(
        itemId = "i",
        name = "n",
        status = status.wireName,
        quality = quality.wireName,
        progress = progress,
        downloadedBytes = downloaded,
        totalBytes = total,
    )

    @Test
    fun `active row text for every in-flight state`() {
        assertEquals("Queued", ActiveDownloadText.of(row(DownloadStatus.QUEUED), null, online = true).status)
        assertEquals("Waiting for network", ActiveDownloadText.of(row(DownloadStatus.QUEUED), null, online = false).status)
        assertEquals("Preparing…", ActiveDownloadText.of(row(DownloadStatus.PREPARING), null, online = true).status)
        val retrying = ActiveDownloadText.of(row(DownloadStatus.PREPARING), LiveTransfer(phase = DownloadPhase.RETRYING), online = true)
        assertEquals("Retrying…", retrying.status)
        assertTrue(retrying.note!!.contains("Retrying"))
        val downloading =
            ActiveDownloadText.of(
                row(DownloadStatus.DOWNLOADING, progress = 0.43, downloaded = 182_000_000, total = 420_000_000),
                LiveTransfer(bytesPerSecond = 3_100_000, phase = DownloadPhase.RESTARTING),
                online = true,
            )
        assertEquals("43% · 182 MB of ~420 MB · 3.1 MB/s · about 1 min left", downloading.status)
        assertEquals(0.43f, downloading.fraction!!, 1e-6f)
        assertTrue(downloading.note!!.contains("can't resume"))
        // Original has a real size: no "~".
        val original =
            ActiveDownloadText.of(
                row(DownloadStatus.DOWNLOADING, DownloadQuality.ORIGINAL, 0.5, 500_000_000, 1_000_000_000),
                null,
                online = true,
            )
        assertEquals("50% · 500 MB of 1.0 GB", original.status)
    }
}
