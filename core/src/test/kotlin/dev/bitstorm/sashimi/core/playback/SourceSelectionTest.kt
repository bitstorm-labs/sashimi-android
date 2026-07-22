package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun source(
    transcodingUrl: String? = null,
    directStreamUrl: String? = null,
) = MediaSourceInfo(
    id = "ms1",
    transcodingUrl = transcodingUrl,
    directStreamUrl = directStreamUrl,
)

class SourceSelectionTest {
    @Test
    fun `transcoding url wins`() {
        val choice = SourceSelector.choose(source(transcodingUrl = "/videos/x/master.m3u8", directStreamUrl = "/videos/x/stream"))
        assertTrue(choice is SourceChoice.Transcode)
        assertEquals("/videos/x/master.m3u8", (choice as SourceChoice.Transcode).path)
    }

    @Test
    fun `direct stream when no transcode`() {
        val choice = SourceSelector.choose(source(directStreamUrl = "/videos/x/stream.mkv"))
        assertTrue(choice is SourceChoice.DirectStream)
        assertEquals("/videos/x/stream.mkv", (choice as SourceChoice.DirectStream).path)
    }

    @Test
    fun `direct play when neither url present`() {
        assertEquals(SourceChoice.DirectPlay, SourceSelector.choose(source()))
    }

    @Test
    fun `empty strings fall through`() {
        assertEquals(SourceChoice.DirectPlay, SourceSelector.choose(source(transcodingUrl = "", directStreamUrl = "")))
        assertTrue(SourceSelector.choose(source(transcodingUrl = "", directStreamUrl = "/s")) is SourceChoice.DirectStream)
    }
}

class NegotiationFlagsTest {
    @Test
    fun `default enables everything`() {
        val f = NegotiationFlags.derive(forceDirectPlay = false, forceTranscode = false)
        assertTrue(f.enableDirectPlay)
        assertTrue(f.enableDirectStream)
        assertTrue(f.enableTranscoding)
    }

    @Test
    fun `force direct play disables stream and transcode`() {
        val f = NegotiationFlags.derive(forceDirectPlay = true, forceTranscode = false)
        assertTrue(f.enableDirectPlay)
        assertFalse(f.enableDirectStream)
        assertFalse(f.enableTranscoding)
    }

    @Test
    fun `force transcode disables direct play and stream`() {
        val f = NegotiationFlags.derive(forceDirectPlay = false, forceTranscode = true)
        assertFalse(f.enableDirectPlay)
        assertFalse(f.enableDirectStream)
        assertTrue(f.enableTranscoding)
    }

    @Test
    fun `explicit transcode overrides force direct play`() {
        // Quality pick (forceTranscode) must win over the global Force Direct Play.
        val f = NegotiationFlags.derive(forceDirectPlay = true, forceTranscode = true)
        assertFalse(f.enableDirectPlay)
        assertFalse(f.enableDirectStream)
        assertTrue(f.enableTranscoding)
    }
}

class BitrateResolverTest {
    @Test
    fun `session override wins`() {
        assertEquals(8_000_000, BitrateResolver.effectiveMaxBitrate(sessionOverride = 8_000_000, settingsMaxBitrate = 20_000_000))
    }

    @Test
    fun `settings used when no override, zero means auto`() {
        assertEquals(20_000_000, BitrateResolver.effectiveMaxBitrate(sessionOverride = null, settingsMaxBitrate = 20_000_000))
        assertEquals(null, BitrateResolver.effectiveMaxBitrate(sessionOverride = null, settingsMaxBitrate = 0))
    }
}
