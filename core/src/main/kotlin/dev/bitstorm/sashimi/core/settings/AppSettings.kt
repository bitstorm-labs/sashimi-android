package dev.bitstorm.sashimi.core.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing feature toggles (SharedPreferences-backed). M2 needs only
 * `showQualityBadges` (the "showQualityBadges" key, gating the resolution chip
 * on cover art — port of the Swift @AppStorage("showQualityBadges")). The full
 * settings surface (playback toggles, bitrate, languages) lands in M5.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _showQualityBadges = MutableStateFlow(prefs.getBoolean(KEY_QUALITY_BADGES, true))
    val showQualityBadges: StateFlow<Boolean> = _showQualityBadges.asStateFlow()

    fun setShowQualityBadges(value: Boolean) {
        _showQualityBadges.value = value
        prefs.edit().putBoolean(KEY_QUALITY_BADGES, value).apply()
    }

    companion object {
        private const val PREFS = "sashimi_settings"
        private const val KEY_QUALITY_BADGES = "showQualityBadges"
    }
}
