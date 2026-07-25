package dev.bitstorm.sashimi.core.playback

/**
 * Player Quality menu options, ported from the Swift `QualityOption`. Any non-
 * Auto pick forces a transcode at the given cap (so the cap visibly takes effect
 * even when the source would otherwise direct-play under it). Auto defers to the
 * Settings maxBitrate (0 = no cap).
 */
enum class QualityOption(
    val label: String,
    val maxBitrate: Int?,
    /**
     * Output width cap sent as a device-profile Width condition. Without this
     * the labels were cosmetic: MaxStreamingBitrate is a bitrate ceiling only,
     * so picking "720p" delivered 1080p at a lower bitrate.
     */
    val maxWidth: Int?,
) {
    AUTO("Auto", null, null),
    P1080("1080p", 20_000_000, 1920),
    P720("720p", 8_000_000, 1280),
    P480("480p", 4_000_000, 854),
    ;

    /** A non-Auto pick forces a transcode. */
    val forcesTranscode: Boolean get() = this != AUTO
}

/**
 * Resolves the effective streaming bitrate cap. A per-session Quality override
 * wins; otherwise the Settings value, where 0 means Auto (no cap → null). Ported
 * from Swift `PlaybackSelection.effectiveMaxBitrate`.
 */
object BitrateResolver {
    fun effectiveMaxBitrate(
        sessionOverride: Int?,
        settingsMaxBitrate: Int,
    ): Int? = sessionOverride ?: settingsMaxBitrate.takeIf { it > 0 }
}
