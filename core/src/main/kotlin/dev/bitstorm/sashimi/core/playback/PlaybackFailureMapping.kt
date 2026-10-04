package dev.bitstorm.sashimi.core.playback

/**
 * Maps a Media3 `PlaybackException.errorCode` onto a [PlaybackFailure]. By the
 * documented code ranges rather than the constants, so :core stays free of
 * Media3 and the mapping is unit tested: 1xxx miscellaneous (1002 behind live
 * window, 1003 timeout), 2xxx input/output, 3xxx parsing, 4xxx decoding, 5xxx
 * audio track, 6xxx DRM.
 */
object PlaybackFailureMapping {
    private const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
    private const val ERROR_CODE_TIMEOUT = 1003

    fun fromMedia3ErrorCode(code: Int): PlaybackFailure =
        when (code) {
            ERROR_CODE_BEHIND_LIVE_WINDOW, ERROR_CODE_TIMEOUT -> PlaybackFailure.NETWORK
            in 2000..2999 -> PlaybackFailure.NETWORK
            in 3000..3999 -> PlaybackFailure.SOURCE
            in 4000..5999 -> PlaybackFailure.DECODE
            else -> PlaybackFailure.OTHER
        }
}
