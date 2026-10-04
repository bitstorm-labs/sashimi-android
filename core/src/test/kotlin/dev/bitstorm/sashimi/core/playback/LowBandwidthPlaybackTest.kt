package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier table, step-down ladder, Auto cap and locality: ported from Apple's LowBandwidthPlaybackTests. */
class LowBandwidthPlaybackTest {
    @Test
    fun `tier labels read resolution and bitrate`() {
        assertEquals("Auto", QualityOption.AUTO.menuLabel)
        assertEquals("1080p · 20 Mbps", QualityOption.P1080.menuLabel)
        assertEquals("720p · 2 Mbps", QualityOption.P720_LOW.menuLabel)
        assertEquals("360p · 720 kbps", QualityOption.P360.menuLabel)
        assertEquals(
            listOf(QualityOption.P720_LOW, QualityOption.P480_LOW, QualityOption.P360),
            QualityOption.entries.filter { it.lowBandwidth },
        )
    }

    @Test
    fun `the floor is 720 kbps`() {
        assertEquals(720_000, QualityOption.floorBitrate)
    }

    @Test
    fun `step down roughly halves, 20 to 8 to 4 to 2 to 1 Mbps to 720 kbps`() {
        val ladder = generateSequence(QualityOption.P1080) { QualityOption.steppedDown(it.maxBitrate!!) }.toList()
        assertEquals(
            listOf(
                QualityOption.P1080,
                QualityOption.P720,
                QualityOption.P480,
                QualityOption.P720_LOW,
                QualityOption.P480_LOW,
                QualityOption.P360,
            ),
            ladder,
        )
    }

    @Test
    fun `a 9_5 Mbps stream steps to 4 Mbps, a 60 Mbps remux to 20`() {
        assertEquals(QualityOption.P480, QualityOption.steppedDown(9_500_000))
        assertEquals(QualityOption.P1080, QualityOption.steppedDown(60_000_000))
    }

    @Test
    fun `just above the floor steps to the floor, at the floor there is nothing`() {
        assertEquals(QualityOption.P360, QualityOption.steppedDown(900_000))
        assertNull(QualityOption.steppedDown(720_000))
        assertNull(QualityOption.steppedDown(500_000))
    }

    @Test
    fun `auto cap unmeasured is 4 Mbps remote and 100 Mbps local`() {
        assertEquals(4_000_000, AutoBitrate.cap(null, isLocalServer = false))
        assertEquals(100_000_000, AutoBitrate.cap(null, isLocalServer = true))
        assertEquals(4_000_000, AutoBitrate.cap(0, isLocalServer = false))
    }

    @Test
    fun `auto cap measured is 85 percent clamped to the floor and 100 Mbps`() {
        assertEquals(8_500_000, AutoBitrate.cap(10_000_000, isLocalServer = false))
        assertEquals(720_000, AutoBitrate.cap(300_000, isLocalServer = false))
        assertEquals(100_000_000, AutoBitrate.cap(900_000_000, isLocalServer = true))
        // A measurement wins over the LAN assumption: a weak Wi-Fi LAN is still weak.
        assertEquals(1_700_000, AutoBitrate.cap(2_000_000, isLocalServer = true))
    }

    @Test
    fun `auto width follows the cap`() {
        assertEquals(640, AutoBitrate.maxWidth(720_000))
        assertEquals(854, AutoBitrate.maxWidth(2_000_000))
        assertEquals(1280, AutoBitrate.maxWidth(8_000_000))
        assertEquals(1920, AutoBitrate.maxWidth(20_000_000))
        assertNull(AutoBitrate.maxWidth(40_000_000))
    }

    @Test
    fun `re-encode width only for a video re-encode of a wider source`() {
        assertEquals(854, AutoBitrate.reencodeWidth(4_000_000, 3840, listOf("VideoCodecNotSupported")))
        assertEquals(854, AutoBitrate.reencodeWidth(4_000_000, 1920, listOf("ContainerBitrateExceedsLimit")))
        // Audio-only conversion: the video is copied, a width would force an encode.
        assertNull(AutoBitrate.reencodeWidth(4_000_000, 3840, listOf("AudioCodecNotSupported")))
        // Already narrow enough.
        assertNull(AutoBitrate.reencodeWidth(4_000_000, 640, listOf("VideoCodecNotSupported")))
        assertNull(AutoBitrate.reencodeWidth(4_000_000, 3840, null))
    }

    @Test
    fun `locality - LAN addresses are local, Tailscale and public hosts are remote`() {
        listOf(
            "http://192.168.86.151:9096",
            "http://10.0.0.2:8096/",
            "http://172.20.1.1:8096",
            "http://localhost:8096",
            "http://jellyfin.local:8096",
            "http://nas:8096",
            "http://[fd00::1]:8096",
        ).forEach { assertTrue(it, ServerLocality.isLocal(it)) }
        listOf(
            "http://100.92.236.87:9096",
            "https://fin.example.com",
            "http://172.32.0.1:8096",
            "http://8.8.8.8",
            null,
            "",
        ).forEach { assertFalse("$it", ServerLocality.isLocal(it)) }
    }

    @Test
    fun `bitrate labels`() {
        assertEquals("20 Mbps", BitrateLabel.of(20_000_000))
        assertEquals("9.5 Mbps", BitrateLabel.of(9_500_000))
        assertEquals("720 kbps", BitrateLabel.of(720_000))
        assertEquals("Auto · 4 Mbps", BitrateLabel.active(QualityOption.AUTO, 4_000_000))
        assertEquals("Auto", BitrateLabel.active(QualityOption.AUTO, BitrateResolver.NO_CAP))
        assertEquals("480p · 1 Mbps", BitrateLabel.active(QualityOption.P480_LOW, 1_000_000))
    }
}

class PlaybackRecoveryPlanTest {
    private fun decide(
        failure: PlaybackFailure,
        transcoding: Boolean,
        bitrate: Int?,
        retries: Int = 0,
        attempts: Int = 0,
    ) = PlaybackRecoveryPlan.decide(failure, transcoding, bitrate, retries, attempts)

    @Test
    fun `a stall first retries at the same quality`() {
        assertEquals(RecoveryDecision.Retry, decide(PlaybackFailure.STALL, transcoding = true, bitrate = 8_000_000))
    }

    @Test
    fun `a stall after a retry steps down, and keeps stepping without another retry`() {
        assertEquals(
            RecoveryDecision.StepDown(QualityOption.P480),
            decide(PlaybackFailure.STALL, true, 8_000_000, retries = 1, attempts = 1),
        )
        assertEquals(
            RecoveryDecision.StepDown(QualityOption.P720_LOW),
            decide(PlaybackFailure.STALL, true, 4_000_000, retries = 1, attempts = 2),
        )
    }

    @Test
    fun `a stalling direct play steps down to a transcode tier`() {
        assertEquals(
            RecoveryDecision.StepDown(QualityOption.P1080),
            decide(PlaybackFailure.NETWORK, false, 60_000_000, retries = 1, attempts = 1),
        )
    }

    @Test
    fun `a stalling direct play of unknown bitrate falls back to a transcode`() {
        assertEquals(RecoveryDecision.ForceTranscode, decide(PlaybackFailure.STALL, false, null, retries = 1, attempts = 1))
    }

    @Test
    fun `at the floor the ladder gives up`() {
        assertEquals(RecoveryDecision.GiveUp, decide(PlaybackFailure.STALL, true, 720_000, retries = 1, attempts = 6))
    }

    @Test
    fun `a decode failure on direct play goes straight to a transcode`() {
        assertEquals(RecoveryDecision.ForceTranscode, decide(PlaybackFailure.DECODE, false, 30_000_000))
    }

    @Test
    fun `a decode failure on a transcode retries once, steps once, then gives up`() {
        assertEquals(RecoveryDecision.Retry, decide(PlaybackFailure.DECODE, true, 8_000_000, retries = 0, attempts = 1))
        assertEquals(
            RecoveryDecision.StepDown(QualityOption.P480),
            decide(PlaybackFailure.DECODE, true, 8_000_000, retries = 1, attempts = 2),
        )
        assertEquals(RecoveryDecision.GiveUp, decide(PlaybackFailure.DECODE, true, 4_000_000, retries = 1, attempts = 3))
    }

    @Test
    fun `the attempt ceiling always wins`() {
        assertEquals(RecoveryDecision.GiveUp, decide(PlaybackFailure.STALL, true, 20_000_000, attempts = PlaybackRecoveryPlan.MAX_ATTEMPTS))
    }

    @Test
    fun `notices and the exhausted message are human readable`() {
        assertEquals(
            "Lowering quality for your connection · 480p · 1 Mbps",
            PlaybackRecoveryPlan.notice(RecoveryDecision.StepDown(QualityOption.P480_LOW)),
        )
        assertNull(PlaybackRecoveryPlan.notice(RecoveryDecision.GiveUp))
        PlaybackFailure.entries.forEach { assertFalse(PlaybackRecoveryPlan.exhaustedMessage(it).contains("ERROR_CODE")) }
    }

    @Test
    fun `media3 error codes map onto failure kinds`() {
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailureMapping.fromMedia3ErrorCode(2001)) // IO_NETWORK_CONNECTION_FAILED
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailureMapping.fromMedia3ErrorCode(2004)) // IO_BAD_HTTP_STATUS
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailureMapping.fromMedia3ErrorCode(1003)) // TIMEOUT
        assertEquals(PlaybackFailure.SOURCE, PlaybackFailureMapping.fromMedia3ErrorCode(3003)) // PARSING_CONTAINER_UNSUPPORTED
        assertEquals(PlaybackFailure.DECODE, PlaybackFailureMapping.fromMedia3ErrorCode(4001)) // DECODER_INIT_FAILED
        assertEquals(PlaybackFailure.DECODE, PlaybackFailureMapping.fromMedia3ErrorCode(4003)) // DECODING_FAILED
        assertEquals(PlaybackFailure.DECODE, PlaybackFailureMapping.fromMedia3ErrorCode(5001)) // AUDIO_TRACK_INIT_FAILED
        assertEquals(PlaybackFailure.OTHER, PlaybackFailureMapping.fromMedia3ErrorCode(1000))
    }
}

class StallDetectorTest {
    private var now = 0L
    private val detector = StallDetector(clock = { now })

    private fun tick(
        ms: Long,
        buffering: Boolean,
        playing: Boolean = !buffering,
    ): Boolean {
        now += ms
        return detector.update(isBuffering = buffering, wantsToPlay = true, isPlaying = playing)
    }

    @Test
    fun `startup buffering gets 30 seconds`() {
        assertFalse(tick(0, buffering = true))
        assertFalse(tick(29_000, buffering = true))
        assertTrue(tick(1_000, buffering = true))
    }

    @Test
    fun `a mid-stream rebuffer of 12 seconds is a stall`() {
        tick(0, buffering = false)
        assertFalse(tick(1_000, buffering = true))
        assertFalse(tick(11_000, buffering = true))
        assertTrue(tick(1_000, buffering = true))
    }

    @Test
    fun `three short rebuffers within a minute are a stall`() {
        tick(0, buffering = false)
        assertFalse(tick(1_000, buffering = true))
        assertFalse(tick(2_000, buffering = false))
        assertFalse(tick(5_000, buffering = true))
        assertFalse(tick(2_000, buffering = false))
        assertTrue(tick(5_000, buffering = true))
    }

    @Test
    fun `rebuffers spread over more than a minute are not`() {
        tick(0, buffering = false)
        tick(1_000, buffering = true)
        tick(1_000, buffering = false)
        tick(40_000, buffering = true)
        tick(1_000, buffering = false)
        assertFalse(tick(40_000, buffering = true))
    }

    @Test
    fun `buffering after a seek is not counted as a rebuffer and gets the startup allowance`() {
        tick(0, buffering = false)
        detector.noteSeek()
        assertFalse(tick(1_000, buffering = true))
        assertFalse(tick(15_000, buffering = true))
    }

    @Test
    fun `paused buffering never stalls`() {
        now = 0
        repeat(10) { assertFalse(detector.update(isBuffering = true, wantsToPlay = false, isPlaying = false).also { now += 10_000 }) }
    }
}
