package dev.bitstorm.sashimi.core.playback

import kotlin.math.min

/**
 * The kind of link playback runs over, as far as Auto cares.
 *
 * Readings are remembered per kind, so a cellular stretch never drags down
 * what home Wi-Fi is trusted with (Apple keys wired/wireless only; a phone on
 * mobile data is the case Apple's Apple TV never meets).
 */
enum class LinkKind(
    internal val key: String,
) {
    /** Ethernet (an Android TV box, a docked tablet): trusted with what it has measured. */
    WIRED("wired"),

    /** Wi-Fi and anything else unmetered: VBR peaks need headroom. */
    WIRELESS("wireless"),

    /**
     * Billed by the byte (mobile data, a metered hotspot). Treated as wireless
     * for the ceiling, and Auto never steps itself back up on it: spending a
     * viewer's data on a higher tier is not the player's call.
     */
    METERED("metered"),
    ;

    companion object {
        /** Metered wins over Ethernet: a metered wired link is still billed. */
        fun classify(
            isEthernet: Boolean,
            isMetered: Boolean,
        ): LinkKind =
            when {
                isMetered -> METERED
                isEthernet -> WIRED
                else -> WIRELESS
            }
    }
}

/** What the adaptive-Auto check says about a fresh reading. */
sealed interface UpgradeDecision {
    /** Nothing to gain, or not Auto's stream to change. */
    data object None : UpgradeDecision

    /** Worth doing, but not yet: the next reading asks again. */
    data class Later(
        val waitMs: Long,
    ) : UpgradeDecision

    /** Rebuild at the current position on Auto. */
    data object Now : UpgradeDecision
}

/** What the player knows when a fresh speed reading arrives mid-stream. */
data class SustainedUpgradeContext(
    /** The viewer chose Auto (a stall step-down from Auto keeps this true). See [AdaptiveAutoState.isViewerOnAuto]. */
    val isAuto: Boolean,
    val linkKind: LinkKind,
    /** The Auto cap now: the probe's measurement with headroom, or its default. */
    val autoCap: Int,
    /** The cap the current stream was negotiated with. */
    val activeCap: Int?,
    val sourceBitrate: Int?,
    /** The lowest recent reading for this server and link kind; see [SustainedThroughputStore]. */
    val sustainedBitrate: Int?,
    val isTranscoding: Boolean,
    /** A rebuild is in flight or the player is buffering. */
    val isBusy: Boolean,
    val msSinceRecovery: Long?,
    /** Since the last automatic quality change, either way. */
    val msSinceQualityChange: Long?,
    /** Upgrades this session that ran straight into a stall. */
    val failedUpgrades: Int,
)

/**
 * Adaptive Auto, ported from the Apple client's `PlaybackSelection`
 * (`wirelessCeiling`, `sustainedUpgrade`, `adaptiveCooldown`).
 *
 * Auto starts from the probe, but the probe is a few seconds of one burst.
 * What playback itself downloaded at is the better evidence, so it is
 * remembered ([SustainedThroughputStore]) and used two ways:
 *  - the ceiling a non-wired link is trusted with is half its lowest recent
 *    reading, because a 4K remux's VBR peaks run to about twice its average;
 *  - mid-stream, a transcode Auto held below its source is rebuilt on Auto
 *    once the link shows it carries the source, or at least twice the cap.
 */
object AdaptiveAuto {
    /** The non-wired ceiling while a link has no remembered reading: a light, smooth 4K transcode. */
    const val SMOOTH_4K_BITRATE = 24_000_000

    /** A wireless link is trusted with its sustained rate divided by this. */
    const val WIRELESS_SUSTAINED_HEADROOM = 2.0

    /** An upgrade must at least double the cap (or reach the source) to be worth a rebuild. */
    const val UPGRADE_MINIMUM_RATIO = 2

    /** Time between automatic quality changes; each one restarts the stream. */
    const val ADAPTIVE_COOLDOWN_MS = 120_000L

    /** The cool-down doubles per failed upgrade, up to 2^5 = 32x. */
    const val MAX_BACKOFF_DOUBLINGS = 5

    /** No upgrade within this long of a recovery or while one is in flight. */
    const val RECOVERY_QUIET_MS = 15_000L

    /**
     * The most a non-wired link is trusted to carry: half its remembered
     * sustained rate, never more than [cap], or [SMOOTH_4K_BITRATE] while
     * nothing is remembered. Never below the lowest tier.
     */
    fun wirelessCeiling(
        cap: Int,
        sustainedBitrate: Int?,
    ): Int {
        val ceiling =
            if (sustainedBitrate == null || sustainedBitrate <= 0) {
                min(cap, SMOOTH_4K_BITRATE)
            } else {
                min(cap.toDouble(), sustainedBitrate / WIRELESS_SUSTAINED_HEADROOM).toInt()
            }
        return ceiling.coerceAtLeast(min(cap, AutoBitrate.MINIMUM_MEASURED_CAP))
    }

    /**
     * The bitrate Auto asks for: the probe's [cap] on a wired link (it carries
     * what it measured), [wirelessCeiling] on anything else.
     */
    fun linkCeiling(
        cap: Int,
        linkKind: LinkKind,
        sustainedBitrate: Int?,
    ): Int = if (linkKind == LinkKind.WIRED) cap else wirelessCeiling(cap, sustainedBitrate)

    fun cooldownMs(failedUpgrades: Int): Long = ADAPTIVE_COOLDOWN_MS shl failedUpgrades.coerceIn(0, MAX_BACKOFF_DOUBLINGS)

    /**
     * Whether to rebuild a stream Auto (or a stall step-down from it) holds
     * below its source, now that playback shows the link carries much more.
     */
    fun sustainedUpgrade(c: SustainedUpgradeContext): UpgradeDecision {
        if (!c.isAuto || !c.isTranscoding || c.linkKind == LinkKind.METERED) return UpgradeDecision.None
        val source = c.sourceBitrate?.takeIf { it > 0 } ?: return UpgradeDecision.None
        val active = c.activeCap?.takeIf { it > 0 } ?: return UpgradeDecision.None
        val sustained = c.sustainedBitrate?.takeIf { it > 0 } ?: return UpgradeDecision.None
        if (active >= source) return UpgradeDecision.None
        val ceiling =
            if (c.linkKind == LinkKind.WIRED) min(c.autoCap, sustained) else wirelessCeiling(c.autoCap, sustained)
        if (ceiling < source && ceiling.toLong() < active.toLong() * UPGRADE_MINIMUM_RATIO) return UpgradeDecision.None

        var wait = if (c.isBusy) RECOVERY_QUIET_MS else 0L
        c.msSinceRecovery?.let { wait = maxOf(wait, RECOVERY_QUIET_MS - it) }
        c.msSinceQualityChange?.let { wait = maxOf(wait, cooldownMs(c.failedUpgrades) - it) }
        return if (wait > 0) UpgradeDecision.Later(wait) else UpgradeDecision.Now
    }
}

/**
 * One playback session's adaptive-Auto bookkeeping, with an injected clock.
 * Lives as long as the player (across Up Next), like the Apple player's
 * fields of the same names.
 */
class AdaptiveAutoState(
    private val clock: () -> Long,
) {
    /** The tier in force is a stall step-down Auto made, not the viewer's pick. */
    var autoSteppedDown: Boolean = false
        private set

    var failedUpgrades: Int = 0
        private set

    private var lastChangeAt: Long? = null
    private var lastUpgradeAt: Long? = null

    /**
     * The viewer chose Auto: Auto itself, or a tier a stall step-down put in
     * its place. A Settings cap or a quality the viewer picked is never Auto.
     */
    fun isViewerOnAuto(
        settingsMaxBitrate: Int,
        selected: QualityOption,
    ): Boolean = settingsMaxBitrate == AUTO_SETTING && (selected == QualityOption.AUTO || autoSteppedDown)

    private companion object {
        /** AppSettings.maxBitrate's Auto value. */
        const val AUTO_SETTING = 0
    }

    fun msSinceQualityChange(): Long? = lastChangeAt?.let { clock() - it }

    /**
     * A stall stepped the stream down. From Auto, the lower tier stays Auto's
     * to raise again; an upgrade that stalled within its cool-down makes the
     * next one wait twice as long.
     */
    fun onStepDown(fromViewerAuto: Boolean) {
        val now = clock()
        if (fromViewerAuto) autoSteppedDown = true
        val upgradedAt = lastUpgradeAt
        if (upgradedAt != null && now - upgradedAt < AdaptiveAuto.cooldownMs(failedUpgrades)) failedUpgrades++
        lastChangeAt = now
    }

    /** Auto stepped itself back up. */
    fun onUpgrade() {
        val now = clock()
        autoSteppedDown = false
        lastChangeAt = now
        lastUpgradeAt = now
    }

    /** The viewer picked a quality: it is theirs, not Auto's. */
    fun onViewerPick() {
        autoSteppedDown = false
    }
}
