package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The remembered playback throughput and the meter that feeds it (#155). */
class SustainedThroughputTest {
    private val persistence = InMemoryThroughputPersistence()
    private val store = SustainedThroughputStore(persistence)
    private val server = "http://192.168.86.151:9096"
    private val enough = SustainedThroughputStore.MINIMUM_BYTES

    @Test
    fun `the lowest of the last five readings is the link's sustained rate`() {
        listOf(90, 40, 70, 80, 95).forEach { store.record(server, LinkKind.WIRELESS, it * 1_000_000, enough) }
        assertEquals(40_000_000, store.sustainedBitrate(server, LinkKind.WIRELESS))
        // A sixth pushes out the oldest; the slow 40 stays until four more replace it.
        store.record(server, LinkKind.WIRELESS, 100_000_000, enough)
        assertEquals(40_000_000, store.sustainedBitrate(server, LinkKind.WIRELESS))
        repeat(4) { store.record(server, LinkKind.WIRELESS, 100_000_000, enough) }
        assertEquals(100_000_000, store.sustainedBitrate(server, LinkKind.WIRELESS))
    }

    @Test
    fun `readings over too little video are ignored`() {
        store.record(server, LinkKind.WIRELESS, 5_000_000, enough - 1)
        store.record(server, LinkKind.WIRELESS, 0, enough)
        assertNull(store.sustainedBitrate(server, LinkKind.WIRELESS))
    }

    @Test
    fun `kept per server and per link kind`() {
        store.record(server, LinkKind.WIRELESS, 50_000_000, enough)
        store.record(server, LinkKind.METERED, 3_000_000, enough)
        assertEquals(50_000_000, store.sustainedBitrate(server, LinkKind.WIRELESS))
        assertEquals(3_000_000, store.sustainedBitrate(server, LinkKind.METERED))
        assertNull(store.sustainedBitrate(server, LinkKind.WIRED))
        assertNull(store.sustainedBitrate("https://jellyfin.example.com", LinkKind.WIRELESS))
        assertNull(store.sustainedBitrate(null, LinkKind.WIRELESS))
    }

    @Test
    fun `the same server is one key whatever its case or trailing slash`() {
        store.record("HTTP://Jelly.Example.com:8920/jf/", LinkKind.WIRELESS, 30_000_000, enough)
        assertEquals(30_000_000, store.sustainedBitrate("http://jelly.example.com:8920/jf", LinkKind.WIRELESS))
    }

    @Test
    fun `persisted, so a new store reads what the last one wrote`() {
        store.record(server, LinkKind.WIRED, 400_000_000, enough)
        assertEquals(400_000_000, SustainedThroughputStore(persistence).sustainedBitrate(server, LinkKind.WIRED))
    }

    // MARK: Meter

    private val meter = ThroughputMeter(minimumBytes = 1_000_000)
    private val ms = 1_000_000L

    /** Feeds [chunks] reads of [size] bytes, [stepMs] apart, on [transfer]. */
    private fun feed(
        transfer: Any,
        startMs: Long,
        chunks: Int,
        size: Int,
        stepMs: Long,
    ): ThroughputMeter.Reading? {
        var reading: ThroughputMeter.Reading? = null
        for (i in 1..chunks) meter.bytesTransferred(transfer, size, (startMs + i * stepMs) * ms)?.let { reading = it }
        return reading
    }

    @Test
    fun `a steady transfer reads its rate once enough bytes have arrived`() {
        val t = Any()
        meter.transferStarted(t, 0)
        // 100 KB every 10 ms = 80 Mbps; 10 reads = 1 MB.
        assertNull(feed(t, 0, 9, 100_000, 10))
        val reading = meter.bytesTransferred(t, 100_000, 100 * ms)
        assertNotNull(reading)
        assertEquals(80_000_000, reading!!.bitsPerSecond)
        assertEquals(1_000_000L, reading.bytes)
    }

    @Test
    fun `the server's time to first byte is not counted`() {
        // transferStarted fires once the response has opened, so a 3 s wait for
        // a transcode segment happens before it and never reaches the meter.
        val t = Any()
        meter.transferStarted(t, 3_000 * ms)
        val reading = feed(t, 3_000, 10, 100_000, 10)
        assertEquals(80_000_000, reading!!.bitsPerSecond)
    }

    @Test
    fun `an idle gap and the burst that ends it do not count`() {
        val t = Any()
        meter.transferStarted(t, 0)
        assertNull(feed(t, 0, 5, 100_000, 10)) // 500 KB in 50 ms
        // The player stops reading for 2 s, then drains a socket buffer at once.
        assertNull(meter.bytesTransferred(t, 400_000, 2_050 * ms))
        val reading = feed(t, 2_050, 5, 100_000, 10)
        // Only the 1 MB delivered over 100 ms of link time: 80 Mbps, not ~4.
        assertEquals(80_000_000, reading!!.bitsPerSecond)
    }

    @Test
    fun `a progressive transfer stops counting once loading pauses, HLS segments do not`() {
        meter.reset(cutOnLoadPause = true)
        val file = Any()
        meter.transferStarted(file, 0)
        assertNull(feed(file, 0, 5, 100_000, 10))
        meter.loadingPaused()
        // Resumed reads of the same file come at the playback rate: ignored.
        assertNull(feed(file, 50, 20, 100_000, 10))
        // A new transfer (a seek opens a new range) counts again.
        val afterSeek = Any()
        meter.transferStarted(afterSeek, 300 * ms)
        assertNotNull(feed(afterSeek, 300, 10, 100_000, 10))

        meter.reset(cutOnLoadPause = false)
        val segment = Any()
        meter.transferStarted(segment, 0)
        assertNull(feed(segment, 0, 5, 100_000, 10))
        meter.loadingPaused()
        assertNotNull(feed(segment, 50, 5, 100_000, 10))
    }

    @Test
    fun `a reset forgets the last stream's transfers and bytes`() {
        val t = Any()
        meter.transferStarted(t, 0)
        feed(t, 0, 9, 100_000, 10)
        meter.reset(cutOnLoadPause = false)
        assertNull(meter.bytesTransferred(t, 100_000, 100 * ms))
        val next = Any()
        meter.transferStarted(next, 0)
        assertNull(feed(next, 0, 9, 100_000, 10))
    }

    @Test
    fun `an ended transfer is forgotten`() {
        val t = Any()
        meter.transferStarted(t, 0)
        meter.transferEnded(t)
        assertNull(feed(t, 0, 20, 100_000, 10))
    }
}
