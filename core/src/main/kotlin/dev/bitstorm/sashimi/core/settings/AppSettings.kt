package dev.bitstorm.sashimi.core.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing feature toggles (SharedPreferences-backed), the Android port of
 * the Swift `PlaybackSettings` `@AppStorage` surface. Every key name and default
 * mirrors the reference exactly — notably `maxBitrate` defaults to **0 (Auto /
 * no cap)**: a non-zero default would silently throttle users.
 *
 * Each setting is exposed as a [StateFlow] so Compose observers recompose on
 * change, and the playback pipeline (which is Compose-free) reads `.value`.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _showQualityBadges = MutableStateFlow(prefs.getBoolean(KEY_QUALITY_BADGES, true))
    val showQualityBadges: StateFlow<Boolean> = _showQualityBadges.asStateFlow()

    private val _showReviewRatings = MutableStateFlow(prefs.getBoolean(KEY_REVIEW_RATINGS, true))
    val showReviewRatings: StateFlow<Boolean> = _showReviewRatings.asStateFlow()

    private val _maxBitrate = MutableStateFlow(prefs.getInt(KEY_MAX_BITRATE, 0))
    val maxBitrate: StateFlow<Int> = _maxBitrate.asStateFlow()

    private val _autoPlayNextEpisode = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true))
    val autoPlayNextEpisode: StateFlow<Boolean> = _autoPlayNextEpisode.asStateFlow()

    private val _autoSkipIntro = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SKIP_INTRO, false))
    val autoSkipIntro: StateFlow<Boolean> = _autoSkipIntro.asStateFlow()

    private val _autoSkipCredits = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SKIP_CREDITS, false))
    val autoSkipCredits: StateFlow<Boolean> = _autoSkipCredits.asStateFlow()

    private val _resumeThresholdSeconds = MutableStateFlow(prefs.getInt(KEY_RESUME_THRESHOLD, 30))
    val resumeThresholdSeconds: StateFlow<Int> = _resumeThresholdSeconds.asStateFlow()

    private val _forceDirectPlay = MutableStateFlow(prefs.getBoolean(KEY_FORCE_DIRECT_PLAY, false))
    val forceDirectPlay: StateFlow<Boolean> = _forceDirectPlay.asStateFlow()

    private val _subtitlesEnabled = MutableStateFlow(prefs.getBoolean(KEY_SUBTITLES_ENABLED, false))
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    private val _preferredAudioLanguage = MutableStateFlow(prefs.getString(KEY_PREF_AUDIO_LANG, "") ?: "")
    val preferredAudioLanguage: StateFlow<String> = _preferredAudioLanguage.asStateFlow()

    private val _preferredSubtitleLanguage = MutableStateFlow(prefs.getString(KEY_PREF_SUB_LANG, "") ?: "")
    val preferredSubtitleLanguage: StateFlow<String> = _preferredSubtitleLanguage.asStateFlow()

    fun setShowQualityBadges(value: Boolean) = putBoolean(_showQualityBadges, KEY_QUALITY_BADGES, value)

    fun setShowReviewRatings(value: Boolean) = putBoolean(_showReviewRatings, KEY_REVIEW_RATINGS, value)

    fun setMaxBitrate(value: Int) {
        _maxBitrate.value = value
        prefs.edit().putInt(KEY_MAX_BITRATE, value).apply()
    }

    fun setAutoPlayNextEpisode(value: Boolean) = putBoolean(_autoPlayNextEpisode, KEY_AUTO_PLAY_NEXT, value)

    fun setAutoSkipIntro(value: Boolean) = putBoolean(_autoSkipIntro, KEY_AUTO_SKIP_INTRO, value)

    fun setAutoSkipCredits(value: Boolean) = putBoolean(_autoSkipCredits, KEY_AUTO_SKIP_CREDITS, value)

    fun setResumeThresholdSeconds(value: Int) {
        _resumeThresholdSeconds.value = value
        prefs.edit().putInt(KEY_RESUME_THRESHOLD, value).apply()
    }

    fun setForceDirectPlay(value: Boolean) = putBoolean(_forceDirectPlay, KEY_FORCE_DIRECT_PLAY, value)

    fun setSubtitlesEnabled(value: Boolean) = putBoolean(_subtitlesEnabled, KEY_SUBTITLES_ENABLED, value)

    fun setPreferredAudioLanguage(value: String) = putString(_preferredAudioLanguage, KEY_PREF_AUDIO_LANG, value)

    fun setPreferredSubtitleLanguage(value: String) = putString(_preferredSubtitleLanguage, KEY_PREF_SUB_LANG, value)

    private fun putBoolean(
        flow: MutableStateFlow<Boolean>,
        key: String,
        value: Boolean,
    ) {
        flow.value = value
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun putString(
        flow: MutableStateFlow<String>,
        key: String,
        value: String,
    ) {
        flow.value = value
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val PREFS = "sashimi_settings"
        private const val KEY_QUALITY_BADGES = "showQualityBadges"
        private const val KEY_REVIEW_RATINGS = "showReviewRatings"
        private const val KEY_MAX_BITRATE = "maxBitrate"
        private const val KEY_AUTO_PLAY_NEXT = "autoPlayNextEpisode"
        private const val KEY_AUTO_SKIP_INTRO = "autoSkipIntro"
        private const val KEY_AUTO_SKIP_CREDITS = "autoSkipCredits"
        private const val KEY_RESUME_THRESHOLD = "resumeThresholdSeconds"
        private const val KEY_FORCE_DIRECT_PLAY = "forceDirectPlay"
        private const val KEY_SUBTITLES_ENABLED = "subtitlesEnabled"
        private const val KEY_PREF_AUDIO_LANG = "preferredAudioLanguage"
        private const val KEY_PREF_SUB_LANG = "preferredSubtitleLanguage"

        /** Max Bitrate menu options in bits/sec (0 = Auto). */
        val MAX_BITRATE_OPTIONS =
            linkedMapOf(
                "Auto" to 0,
                "40 Mbps" to 40_000_000,
                "20 Mbps" to 20_000_000,
                "10 Mbps" to 10_000_000,
                "8 Mbps" to 8_000_000,
                "4 Mbps" to 4_000_000,
                "2 Mbps" to 2_000_000,
            )
    }
}
