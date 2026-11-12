package dev.bitstorm.sashimi.core.settingssync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The shared settings-sync schema (WORK-106, plans/sashimi/2026-11-12-settings-sync-schema.md).
 * The same key names, value formats and option lists on Apple, Android and Roku,
 * so whatever one device writes the others can apply.
 */
object SettingsSyncSchema {
    /** DisplayPreferences id and client, per user and server. */
    const val PREFS_ID = "sashimi-settings"
    const val PREFS_CLIENT = "sashimi"
    const val CUSTOM_PREFS = "CustomPrefs"
    const val SCHEMA_KEY = "schema"
    const val SCHEMA_VERSION = 1

    const val AUTO_PLAY_NEXT_EPISODE = "autoPlayNextEpisode"
    const val AUTO_SKIP_INTRO = "autoSkipIntro"
    const val AUTO_SKIP_CREDITS = "autoSkipCredits"
    const val RESUME_THRESHOLD_SECONDS = "resumeThresholdSeconds"
    const val FORCE_DIRECT_PLAY = "forceDirectPlay"
    const val SUBTITLES_ENABLED = "subtitlesEnabled"
    const val PREFERRED_AUDIO_LANGUAGE = "preferredAudioLanguage"
    const val PREFERRED_SUBTITLE_LANGUAGE = "preferredSubtitleLanguage"
    const val TV_SHUFFLE_MODE = "tvShuffleMode"
    const val DEFAULT_VIEW_MODE = "defaultViewMode"
    const val SHOW_QUALITY_BADGES = "showQualityBadges"
    const val SHOW_REVIEW_RATINGS = "showReviewRatings"
    const val USE_EPISODE_RATINGS = "useEpisodeRatings"
    const val USE_24_HOUR_TIME = "use24HourTime"
    const val HOME_ROWS = "homeRows"

    /** Resume Threshold, the same list on every platform: Always, 10 s, 30 s, 1, 2, 5 and 10 min. */
    val RESUME_THRESHOLDS: List<Int> = listOf(0, 10, 30, 60, 120, 300, 600)

    /** Audio and subtitle languages, in the spec's order. "" (device default) is valid too. */
    val LANGUAGES: List<String> =
        listOf("en", "es", "fr", "de", "it", "pt", "ja", "ko", "zh", "ru", "ar", "hi", "nl", "pl", "sv", "da", "no", "fi")

    val TV_SHUFFLE_MODES: List<String> = listOf("randomEpisode", "randomShowNextEpisode")
    val VIEW_MODES: List<String> = listOf("normal", "zoom", "stretch")

    val BOOL_KEYS: Set<String> =
        setOf(
            AUTO_PLAY_NEXT_EPISODE,
            AUTO_SKIP_INTRO,
            AUTO_SKIP_CREDITS,
            FORCE_DIRECT_PLAY,
            SUBTITLES_ENABLED,
            SHOW_QUALITY_BADGES,
            SHOW_REVIEW_RATINGS,
            USE_EPISODE_RATINGS,
            USE_24_HOUR_TIME,
        )

    /** Every key Android syncs. `sideMenu` comes later, with side-menu-unify. */
    val ANDROID_KEYS: List<String> =
        listOf(
            AUTO_PLAY_NEXT_EPISODE,
            AUTO_SKIP_INTRO,
            AUTO_SKIP_CREDITS,
            RESUME_THRESHOLD_SECONDS,
            FORCE_DIRECT_PLAY,
            SUBTITLES_ENABLED,
            PREFERRED_AUDIO_LANGUAGE,
            PREFERRED_SUBTITLE_LANGUAGE,
            TV_SHUFFLE_MODE,
            DEFAULT_VIEW_MODE,
            SHOW_QUALITY_BADGES,
            SHOW_REVIEW_RATINGS,
            USE_EPISODE_RATINGS,
            USE_24_HOUR_TIME,
            HOME_ROWS,
        )

    /**
     * Whether [v] is a value this platform can apply for [key]. Anything else
     * (an unknown option, the wrong JSON type) is ignored, never coerced.
     */
    fun isValid(
        key: String,
        v: JsonElement,
    ): Boolean =
        when (key) {
            in BOOL_KEYS -> v.asBool() != null
            RESUME_THRESHOLD_SECONDS -> v.asInt()?.let { it in RESUME_THRESHOLDS } == true
            PREFERRED_AUDIO_LANGUAGE, PREFERRED_SUBTITLE_LANGUAGE -> v.asString()?.let { it == "" || it in LANGUAGES } == true
            TV_SHUFFLE_MODE -> v.asString() in TV_SHUFFLE_MODES
            DEFAULT_VIEW_MODE -> v.asString() in VIEW_MODES
            HOME_ROWS -> HomeRowsLayout.decode(v) != null
            else -> false
        }

    /** A JSON `true`/`false`, never the string "true". */
    fun JsonElement.asBool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

    /** A JSON integer (not 30.5, not "30"). */
    fun JsonElement.asInt(): Int? =
        (this as? JsonPrimitive)
            ?.takeIf { !it.isString && it !is JsonNull }
            ?.longOrNull
            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.toInt()

    fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** One synced setting as stored: the value and the time (epoch ms) it was set on the device that set it. */
data class StampedValue(
    val v: JsonElement,
    val t: Long,
)

/**
 * A CustomPrefs entry is a JSON *string* holding `{"v":…,"t":…}`, because
 * Jellyfin and the readers treat CustomPrefs values as strings.
 */
object StampedValueCodec {
    private val json = Json

    /** Null for anything that is not a string holding an object with `v` and an integer `t` (V11). */
    fun decode(raw: JsonElement?): StampedValue? {
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val v = obj["v"] ?: return null
        val t = (obj["t"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        return StampedValue(v, t)
    }

    fun encode(value: StampedValue): String =
        json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("v" to value.v, "t" to JsonPrimitive(value.t))))
}
