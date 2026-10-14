package dev.bitstorm.sashimi.herotrailer

import dev.bitstorm.sashimi.di.ServiceLocator

/**
 * Everything a detail screen plays in the background: the theme song and the
 * hero trailer. Call sites that start playback or leave the app go through
 * here so neither can be forgotten (the Apple `DetailAmbience`).
 */
object DetailAmbience {
    /** Video is starting: both must be gone before the player's own audio. */
    fun stopForPlayback() {
        ServiceLocator.themeSongs.stopForPlayback()
        ServiceLocator.heroTrailers.stopForPlayback()
    }

    /** The app left the foreground. Nothing ambient should outlive that. */
    fun onAppBackgrounded() {
        ServiceLocator.themeSongs.onAppBackgrounded()
        ServiceLocator.heroTrailers.onAppBackgrounded()
    }
}
