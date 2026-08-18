package dev.bitstorm.sashimi.core.themesong

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeStartPolicyTest {
    /** Counts its calls, so "the probe was never consulted" is assertable. */
    private class FakeProbe(private val playing: Boolean) : OtherAudioProbe {
        var calls = 0
            private set

        override fun isOtherAudioPlaying(): Boolean {
            calls += 1
            return playing
        }
    }

    @Test
    fun `plays when nothing else is using the audio`() {
        val probe = FakeProbe(playing = false)
        assertEquals(
            ThemeStartDecision.PLAY,
            ThemeStartPolicy(probe).decide(settingEnabled = true, alreadyOwnsAudio = false),
        )
    }

    @Test
    fun `defers silently to music or a podcast the user already started`() {
        val probe = FakeProbe(playing = true)
        assertEquals(
            ThemeStartDecision.SKIP_OTHER_AUDIO,
            ThemeStartPolicy(probe).decide(settingEnabled = true, alreadyOwnsAudio = false),
        )
    }

    @Test
    fun `the probe is actually consulted -- it is not decorative`() {
        val probe = FakeProbe(playing = true)
        ThemeStartPolicy(probe).decide(settingEnabled = true, alreadyOwnsAudio = false)
        assertEquals(1, probe.calls)
    }

    @Test
    fun `the setting wins over everything`() {
        val quiet = FakeProbe(playing = false)
        assertEquals(
            ThemeStartDecision.SKIP_SETTING_OFF,
            ThemeStartPolicy(quiet).decide(settingEnabled = false, alreadyOwnsAudio = false),
        )
        val busy = FakeProbe(playing = true)
        assertEquals(
            ThemeStartDecision.SKIP_SETTING_OFF,
            ThemeStartPolicy(busy).decide(settingEnabled = false, alreadyOwnsAudio = true),
        )
    }

    @Test
    fun `a disabled setting short-circuits before touching the audio system`() {
        val probe = FakeProbe(playing = true)
        ThemeStartPolicy(probe).decide(settingEnabled = false, alreadyOwnsAudio = false)
        assertEquals(0, probe.calls)
    }

    @Test
    fun `a theme already playing is not mistaken for someone else's audio`() {
        // isMusicActive cannot tell our own theme from anyone else's, and stays
        // true briefly after playback stops -- so a show-change would gate
        // itself off on the tail of the theme it just faded out.
        val probe = FakeProbe(playing = true)
        assertEquals(
            ThemeStartDecision.PLAY,
            ThemeStartPolicy(probe).decide(settingEnabled = true, alreadyOwnsAudio = true),
        )
    }

    @Test
    fun `owning the audio short-circuits before touching the audio system`() {
        val probe = FakeProbe(playing = true)
        ThemeStartPolicy(probe).decide(settingEnabled = true, alreadyOwnsAudio = true)
        assertEquals(0, probe.calls)
    }

    @Test
    fun `the decision is re-read every time rather than cached`() {
        // The user can start a podcast between two visits; the gate has to see it.
        var playing = false
        val policy = ThemeStartPolicy { playing }
        assertEquals(ThemeStartDecision.PLAY, policy.decide(settingEnabled = true, alreadyOwnsAudio = false))
        playing = true
        assertEquals(ThemeStartDecision.SKIP_OTHER_AUDIO, policy.decide(settingEnabled = true, alreadyOwnsAudio = false))
        playing = false
        assertEquals(ThemeStartDecision.PLAY, policy.decide(settingEnabled = true, alreadyOwnsAudio = false))
    }
}
