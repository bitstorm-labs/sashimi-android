package dev.bitstorm.sashimi.core.settingssync

import android.content.Context
import android.content.SharedPreferences
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.home.HomeRowStore
import dev.bitstorm.sashimi.core.playback.VideoViewMode
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.core.settingssync.SettingsSyncSchema.asBool
import dev.bitstorm.sashimi.core.settingssync.SettingsSyncSchema.asInt
import dev.bitstorm.sashimi.core.settingssync.SettingsSyncSchema.asString
import dev.bitstorm.sashimi.core.shuffle.TvShuffleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * While a remote value is being applied, setters must not report a local
 * change (a device never echoes back what it just received). Applying is
 * synchronous on one thread, so a thread-local flag is enough, and a user
 * change on another thread at the same moment is still reported.
 */
object SettingsSyncSuppression {
    private val applying = ThreadLocal<Boolean>()

    val isApplyingRemote: Boolean get() = applying.get() == true

    fun <T> applyingRemote(block: () -> T): T {
        val previous = applying.get()
        applying.set(true)
        try {
            return block()
        } finally {
            applying.set(previous)
        }
    }
}

/** [AppSettings] and the Home row order as [SyncedSettings]. */
class AppSyncedSettings(
    private val settings: AppSettings,
    private val homeRows: HomeRowSettings,
    /** The saved row order, or null when nothing was ever saved. */
    private val savedRows: () -> List<HomeRowConfig>?,
) : SyncedSettings {
    override val keys: List<String> = SettingsSyncSchema.ANDROID_KEYS

    override fun explicitlySet(): Set<String> {
        val set =
            keys
                .filter {
                    it != SettingsSyncSchema.HOME_ROWS &&
                        it != SettingsSyncSchema.SIDE_MENU &&
                        settings.isStored(
                            it,
                        )
                }.toMutableSet()
        if (savedRows()?.let(HomeRowsLayout::isCustomised) == true) set += SettingsSyncSchema.HOME_ROWS
        val menuRows = HomeRowSettings.normalized(savedRows() ?: HomeRowSettings.DEFAULT_ROWS)
        if (SideMenuLayout.isCustomised(menuRows, settings.showLists.value)) set += SettingsSyncSchema.SIDE_MENU
        return set
    }

    override fun value(
        key: String,
        remote: JsonElement?,
    ): JsonElement =
        with(SettingsSyncSchema) {
            when (key) {
                AUTO_PLAY_NEXT_EPISODE -> JsonPrimitive(settings.autoPlayNextEpisode.value)
                AUTO_SKIP_INTRO -> JsonPrimitive(settings.autoSkipIntro.value)
                AUTO_SKIP_CREDITS -> JsonPrimitive(settings.autoSkipCredits.value)
                RESUME_THRESHOLD_SECONDS -> JsonPrimitive(settings.resumeThresholdSeconds.value)
                FORCE_DIRECT_PLAY -> JsonPrimitive(settings.forceDirectPlay.value)
                SUBTITLES_ENABLED -> JsonPrimitive(settings.subtitlesEnabled.value)
                PREFERRED_AUDIO_LANGUAGE -> JsonPrimitive(settings.preferredAudioLanguage.value)
                PREFERRED_SUBTITLE_LANGUAGE -> JsonPrimitive(settings.preferredSubtitleLanguage.value)
                TV_SHUFFLE_MODE -> JsonPrimitive(settings.tvShuffleMode.value.key)
                DEFAULT_VIEW_MODE -> JsonPrimitive(settings.videoViewModes.defaultMode.value.key)
                SHOW_QUALITY_BADGES -> JsonPrimitive(settings.showQualityBadges.value)
                SHOW_REVIEW_RATINGS -> JsonPrimitive(settings.showReviewRatings.value)
                USE_EPISODE_RATINGS -> JsonPrimitive(settings.useEpisodeRatings.value)
                USE_24_HOUR_TIME -> JsonPrimitive(settings.use24HourTime.value)
                HOME_ROWS -> HomeRowsLayout.write(homeRows.rows.value, remote)
                SIDE_MENU -> SideMenuLayout.write(homeRows.rows.value, settings.showLists.value, remote)
                else -> error("not a synced key: $key")
            }
        }

    override fun apply(
        key: String,
        v: JsonElement,
        t: Long,
        stamps: Map<String, Long>,
    ) = SettingsSyncSuppression.applyingRemote {
        with(SettingsSyncSchema) {
            when (key) {
                AUTO_PLAY_NEXT_EPISODE -> v.asBool()?.let(settings::setAutoPlayNextEpisode)
                AUTO_SKIP_INTRO -> v.asBool()?.let(settings::setAutoSkipIntro)
                AUTO_SKIP_CREDITS -> v.asBool()?.let(settings::setAutoSkipCredits)
                RESUME_THRESHOLD_SECONDS -> v.asInt()?.let(settings::setResumeThresholdSeconds)
                FORCE_DIRECT_PLAY -> v.asBool()?.let(settings::setForceDirectPlay)
                SUBTITLES_ENABLED -> v.asBool()?.let(settings::setSubtitlesEnabled)
                PREFERRED_AUDIO_LANGUAGE -> v.asString()?.let(settings::setPreferredAudioLanguage)
                PREFERRED_SUBTITLE_LANGUAGE -> v.asString()?.let(settings::setPreferredSubtitleLanguage)
                TV_SHUFFLE_MODE -> v.asString()?.let { settings.setTvShuffleMode(TvShuffleMode.fromKey(it)) }
                DEFAULT_VIEW_MODE -> v.asString()?.let { settings.videoViewModes.setDefault(VideoViewMode.fromKey(it)) }
                SHOW_QUALITY_BADGES -> v.asBool()?.let(settings::setShowQualityBadges)
                SHOW_REVIEW_RATINGS -> v.asBool()?.let(settings::setShowReviewRatings)
                USE_EPISODE_RATINGS -> v.asBool()?.let(settings::setUseEpisodeRatings)
                USE_24_HOUR_TIME -> v.asBool()?.let(settings::setUse24HourTime)
                // Both keys carry the rows' relative order: the one with the newer `t` decides it.
                HOME_ROWS ->
                    HomeRowsLayout.entries(v)?.let {
                        val takeOrder = t >= (stamps[SIDE_MENU] ?: Long.MIN_VALUE)
                        homeRows.setRows(HomeRowsLayout.apply(homeRows.rows.value, it, takeOrder))
                    }
                SIDE_MENU ->
                    SideMenuLayout.entries(v)?.let {
                        val keepRowOrder = (stamps[HOME_ROWS] ?: Long.MIN_VALUE) > t
                        val applied = SideMenuLayout.apply(homeRows.rows.value, it, keepRowOrder)
                        homeRows.setRows(applied.rows)
                        applied.showLists?.let(settings::setShowLists)
                    }
                else -> Unit
            }
        }
        Unit
    }
}

/**
 * Wraps the Home row store to notice the user editing Home & Side Menu:
 * reordering or hiding rows reports `homeRows` ([HomeRowsLayout.isUserChange]),
 * moving any entry or flipping a menu switch reports `sideMenu`
 * ([SideMenuLayout.isUserChange]). Automatic saves (a new library, a list
 * row's title, the one-time Search migration) and remote applies are not reported.
 */
class SyncingHomeRowStore(
    private val inner: HomeRowStore,
    private val onUserChange: (String) -> Unit,
) : HomeRowStore {
    @Volatile private var last: List<HomeRowConfig>? = null

    override fun load(): List<HomeRowConfig>? = inner.load().also { last = it }

    override fun save(rows: List<HomeRowConfig>) {
        val before = last ?: inner.load()
        inner.save(rows)
        last = rows
        if (SettingsSyncSuppression.isApplyingRemote) return
        if (HomeRowsLayout.isUserChange(before, rows)) onUserChange(SettingsSyncSchema.HOME_ROWS)
        if (SideMenuLayout.isUserChange(before, rows)) onUserChange(SettingsSyncSchema.SIDE_MENU)
    }
}

/** [SettingsSyncState] in the app's settings file: `settingsSyncEnabled` and `settingsSyncStamps`. */
class PrefsSettingsSyncState(
    context: Context,
) : SettingsSyncState {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
    private val json = Json
    private val lock = Any()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
    }

    override fun stamps(): Map<String, Long>? =
        synchronized(lock) {
            val raw = prefs.getString(KEY_STAMPS, null) ?: return null
            // A corrupt map is treated as never written: the next run seeds again.
            runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrNull()
        }

    override fun updateStamps(change: (Map<String, Long>?) -> Map<String, Long>) {
        synchronized(lock) {
            prefs.edit().putString(KEY_STAMPS, json.encodeToString(change(stamps()))).apply()
        }
    }

    companion object {
        const val KEY_ENABLED = "settingsSyncEnabled"
        const val KEY_STAMPS = "settingsSyncStamps"
    }
}
