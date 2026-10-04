package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.settings.AppSettings

/**
 * Player Quality menu options, matching the Apple client's tier table. Any non-
 * Auto pick forces a transcode at the given cap (so the cap visibly takes effect
 * even when the source would otherwise direct-play under it). Auto defers to the
 * Settings maxBitrate (0 = measured link, see [AutoBitrate]).
 *
 * The three low-bandwidth tiers exist because the old floor, 480p at 4 Mbps,
 * sat above what a weak remote link carries: Plex played where Sashimi could
 * not, because Sashimi had nothing to step down to.
 */
enum class QualityOption(
    /** Resolution part of the label ("720p"); two tiers can share it. */
    val label: String,
    val maxBitrate: Int?,
    /**
     * Output width cap sent as a device-profile Width condition. Without this
     * the labels were cosmetic: MaxStreamingBitrate is a bitrate ceiling only,
     * so picking "720p" delivered 1080p at a lower bitrate.
     */
    val maxWidth: Int?,
    /** Shown under a "Low bandwidth" heading in the quality menu. */
    val lowBandwidth: Boolean = false,
) {
    AUTO("Auto", null, null),
    P1080("1080p", 20_000_000, 1920),
    P720("720p", 8_000_000, 1280),
    P480("480p", 4_000_000, 854),
    P720_LOW("720p", 2_000_000, 1280, lowBandwidth = true),
    P480_LOW("480p", 1_000_000, 854, lowBandwidth = true),
    P360("360p", 720_000, 640, lowBandwidth = true),
    ;

    /** A non-Auto pick forces a transcode. */
    val forcesTranscode: Boolean get() = this != AUTO

    /** Menu wording: "Auto", "720p · 8 Mbps", "360p · 720 kbps". */
    val menuLabel: String
        get() = maxBitrate?.let { "$label · ${BitrateLabel.of(it)}" } ?: label

    companion object {
        /** The lowest tier's bitrate; nothing steps below it. */
        val floorBitrate: Int = entries.mapNotNull { it.maxBitrate }.min()

        private val tiersDescending: List<QualityOption> =
            entries.filter { it.maxBitrate != null }.sortedByDescending { it.maxBitrate }

        /**
         * The tier to step down to from a stream running at [fromBitrate]
         * bits/second: the highest tier at or under half that rate, so each
         * recovery roughly halves the load on the link (20 → 8 → 4 → 2 → 1 Mbps
         * → 720 kbps; a 9.5 Mbps Auto stream steps to 4 Mbps). Null at or
         * below the floor, where there is nothing left to try.
         */
        fun steppedDown(fromBitrate: Int): QualityOption? {
            if (fromBitrate <= floorBitrate) return null
            val half = fromBitrate / 2
            return tiersDescending.firstOrNull { it.maxBitrate!! <= half }
                ?: tiersDescending.last()
        }
    }
}

/** Viewer-facing bitrate text: "20 Mbps", "9.5 Mbps", "720 kbps". */
object BitrateLabel {
    fun of(bitsPerSecond: Int): String {
        if (bitsPerSecond < 1_000_000) return "${bitsPerSecond.coerceAtLeast(0) / 1000} kbps"
        val tenths = Math.round(bitsPerSecond / 100_000.0)
        return if (tenths % 10 == 0L) "${tenths / 10} Mbps" else "${tenths / 10}.${tenths % 10} Mbps"
    }

    /**
     * The quality in force, for the player: "Auto · 4 Mbps", "720p · 2 Mbps",
     * or plain "Auto" when the cap is effectively unlimited.
     */
    fun active(
        quality: QualityOption,
        capBitsPerSecond: Int,
    ): String =
        when {
            quality != QualityOption.AUTO -> quality.menuLabel
            capBitsPerSecond >= AutoBitrate.MAXIMUM_MEASURED_CAP -> "Auto"
            else -> "Auto · ${of(capBitsPerSecond)}"
        }
}

/**
 * Resolves the effective streaming bitrate cap. A per-session Quality override
 * wins; otherwise the Settings value. Ported from Swift
 * `PlaybackSelection.effectiveMaxBitrate`.
 *
 * Three distinct settings values, which is the part worth being careful about:
 *  - 0 (Auto) -> null, and the caller asks [AutoBitrate] for a measured cap
 *  - [AppSettings.UNLIMITED_BITRATE] -> [NO_CAP], an explicit "do not limit me",
 *    for a LAN where transcoding a 4K remux is pure waste
 *  - anything else -> that exact ceiling
 */
object BitrateResolver {
    /**
     * Effectively no ceiling. A concrete number rather than null because the
     * DeviceProfile field is non-nullable, and Jellyfin treats a value above any
     * real source bitrate as no constraint at all.
     */
    const val NO_CAP = 1_000_000_000

    fun effectiveMaxBitrate(
        sessionOverride: Int?,
        settingsMaxBitrate: Int,
    ): Int? {
        sessionOverride?.let { return it }
        return when {
            settingsMaxBitrate == AppSettings.UNLIMITED_BITRATE -> NO_CAP
            settingsMaxBitrate > 0 -> settingsMaxBitrate
            else -> null
        }
    }
}

/**
 * The Auto cap, ported from the Apple client's `PlaybackSelection`: the
 * measured link with headroom, or a default keyed on where the server is when
 * nothing has been measured yet.
 *
 * The old Android Auto was a constant 20 Mbps. On a LAN that needlessly
 * transcoded 4K remuxes; on a weak remote link it asked for five times what
 * the connection carried and there was no way down.
 */
object AutoBitrate {
    /**
     * Unmeasured, server on the local network: assume fast. A failed probe is
     * not evidence of a slow link, and a LAN path is fast until proven otherwise.
     */
    const val UNMEASURED_LOCAL_CAP = 100_000_000

    /**
     * Unmeasured, server reached over the internet, where guessing high really
     * does stall playback. 4 Mbps (480p); the measurement raises it once it lands.
     */
    const val UNMEASURED_REMOTE_CAP = 4_000_000

    /** Fraction of the measured bandwidth to request: headroom for overhead and other traffic. */
    const val MEASURED_HEADROOM = 0.85

    const val MAXIMUM_MEASURED_CAP = 100_000_000

    /** A badly timed probe must not pin quality below the lowest tier. */
    val MINIMUM_MEASURED_CAP: Int = QualityOption.floorBitrate

    fun cap(
        measuredBitrate: Int?,
        isLocalServer: Boolean,
    ): Int {
        if (measuredBitrate == null || measuredBitrate <= 0) {
            return if (isLocalServer) UNMEASURED_LOCAL_CAP else UNMEASURED_REMOTE_CAP
        }
        val withHeadroom = (measuredBitrate * MEASURED_HEADROOM).toInt()
        return withHeadroom.coerceIn(MINIMUM_MEASURED_CAP, MAXIMUM_MEASURED_CAP)
    }

    /**
     * Width to pair with a bitrate cap when no resolution tier was picked. A
     * cap alone is only a ceiling: with no width condition the server re-encodes
     * at the source resolution, so a 4K source capped at 2 Mbps is a blocky 4K
     * encode rather than a watchable 720p one. Null above 25 Mbps, where 4K is
     * plausible and nothing should be downscaled.
     */
    fun maxWidth(capBitsPerSecond: Int): Int? =
        when {
            capBitsPerSecond < 1_000_000 -> 640
            capBitsPerSecond < 6_000_000 -> 854
            capBitsPerSecond < 12_000_000 -> 1280
            capBitsPerSecond < 25_000_000 -> 1920
            else -> null
        }

    /**
     * Whether the server's transcode reasons mean the VIDEO is re-encoded, as
     * opposed to a remux or an audio-only conversion where the video is copied
     * and a width condition would force a needless encode.
     */
    fun reencodesVideo(transcodeReasons: List<String>?): Boolean =
        transcodeReasons.orEmpty().any { reason ->
            reason.startsWith("Video") ||
                reason == "ContainerBitrateExceedsLimit" ||
                reason == "InterlacedVideoNotSupported" ||
                reason == "AnamorphicVideoNotSupported" ||
                reason == "RefFramesNotSupported" ||
                reason == "DirectPlayError"
        }

    /**
     * Second-pass width for an Auto negotiation that came back as a video
     * re-encode: the first request carries no width (a width condition also
     * applies to direct play, and would push a 4K source that fits under the
     * cap down to 1080p), so the re-encode would run at the source resolution.
     * Null when the video is only copied, when the cap needs no downscale, or
     * when the source is already that narrow.
     */
    fun reencodeWidth(
        capBitsPerSecond: Int,
        sourceWidth: Int?,
        transcodeReasons: List<String>?,
    ): Int? {
        if (!reencodesVideo(transcodeReasons)) return null
        val width = maxWidth(capBitsPerSecond) ?: return null
        if (sourceWidth == null || sourceWidth <= width) return null
        return width
    }
}

/**
 * Whether a server is reached over the local network, from its URL host. This
 * is what separates "the probe has not landed" from "the link is slow" without
 * a measurement. Loopback, RFC 1918, link-local and unique-local addresses,
 * `.local` names and single-label hostnames never resolve anywhere but the LAN.
 * A Tailscale (100.64/10) or public address counts as remote: it may well be
 * relayed over a weak link, which is exactly the case that needs the
 * conservative default.
 */
object ServerLocality {
    fun isLocal(serverUrl: String?): Boolean {
        val host = hostOf(serverUrl)?.lowercase()?.trim('[', ']') ?: return false
        if (host.isEmpty()) return false
        if (host.contains(':')) return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        if (host == "localhost" || host.endsWith(".local")) return true
        ipv4Octets(host)?.let { return isPrivateIpv4(it) }
        return !host.contains('.')
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringAfterLast('@')
        // IPv6 literal: [::1]:8096
        if (authority.startsWith("[")) return authority.substringBefore(']').removePrefix("[")
        return authority.substringBefore(':')
    }

    private fun ipv4Octets(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { o -> o.all { it in 0..255 } }
    }

    private fun isPrivateIpv4(o: List<Int>): Boolean =
        when {
            o[0] == 127 || o[0] == 10 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            else -> false
        }
}
