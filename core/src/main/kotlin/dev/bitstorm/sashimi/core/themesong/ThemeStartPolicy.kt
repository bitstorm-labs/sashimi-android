package dev.bitstorm.sashimi.core.themesong

/**
 * Whether audio the user deliberately started — music, a podcast, a game — is
 * playing right now. Abstracted behind an interface so the "defer to the user's
 * own audio" rule is testable without a device; the app backs it with
 * `AudioManager.isMusicActive`.
 */
fun interface OtherAudioProbe {
    fun isOtherAudioPlaying(): Boolean
}

/** Why a show-visit did or did not produce audio. */
enum class ThemeStartDecision {
    PLAY,
    SKIP_SETTING_OFF,
    SKIP_OTHER_AUDIO,
}

/**
 * The gate in front of theme playback.
 *
 * A theme song is decoration. Someone listening to a podcast who opens their
 * library to see what's new should not be silenced by a TV show's title music
 * and left to go restart it by hand — that is worse than not having the feature
 * at all. So when anything else is already playing, the theme silently does not
 * happen. The visit is still spent: skipping is not deferral, and nothing
 * retries when the other audio stops.
 *
 * [alreadyOwnsAudio] exists because the probe cannot tell our own theme apart
 * from anyone else's audio, and `isMusicActive` stays true for a short time
 * after playback stops. When a theme is already playing, we know the answer
 * without asking: nobody else took the audio, because losing focus is what stops
 * us. Consulting the probe there would have a show-change gate itself off on the
 * tail of the theme it just faded out.
 */
class ThemeStartPolicy(private val otherAudio: OtherAudioProbe) {
    fun decide(
        settingEnabled: Boolean,
        alreadyOwnsAudio: Boolean,
    ): ThemeStartDecision =
        when {
            !settingEnabled -> ThemeStartDecision.SKIP_SETTING_OFF
            alreadyOwnsAudio -> ThemeStartDecision.PLAY
            otherAudio.isOtherAudioPlaying() -> ThemeStartDecision.SKIP_OTHER_AUDIO
            else -> ThemeStartDecision.PLAY
        }
}
